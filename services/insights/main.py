"""
D5-B1 — FastAPI insights service.

`POST /insights` takes a portfolio *summary* (aggregates only — never transaction-level data)
and returns a short natural-language summary plus structured highlights.

Two engines, always attempted in this order, and `engine` in the response says honestly which
one produced the text:

1. **LLM** — only attempted if `INSIGHTS_LLM_API_KEY` is set. Timeout is
   `INSIGHTS_LLM_TIMEOUT_SECONDS` (default 10). Sends the portfolio summary only — never
   anything transaction-level, never a credential.
2. **RULE_BASED** — pure Python, deterministic, no network. This is the primary deliverable:
   it must work with no key and no network, and it is what the demo actually relies on.

Any failure on the LLM path (no key, timeout, non-2xx, malformed response) falls back to the
rule-based path silently from the caller's point of view, but never silently in the response:
`engine` always reports which one actually ran.
"""

import logging
import os
import re
from typing import List, Optional

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="Protify Insights", version="0.1.0")

# Every LLM failure here is invisible to the caller by design — the response still arrives, just
# with engine=RULE_BASED. That makes a silent log the difference between a diagnosable fallback
# and an unexplainable one: "why does it always say RULE_BASED" has no answer without this.
log = logging.getLogger("protify.insights")

# Was a hard-coded 2.0, which was tuned for a fast local stub rather than a real provider.
# A hosted model (Gemini Flash, gpt-4o-mini) commonly takes 1-5s and can exceed that under
# load, so 2s did not mean "fail fast" — it meant the LLM path could never win, and every
# response came back RULE_BASED with a correctly configured key and a real bill. The fallback
# is good enough to hide that completely, which is exactly why this needs to be generous and
# configurable rather than clever.
#
# Must stay BELOW the Java caller's own timeout (insights.client.timeout-ms), or InsightsClient
# gives up first and its fallback runs while this service is still waiting on the provider.
LLM_TIMEOUT_SECONDS = float(os.environ.get("INSIGHTS_LLM_TIMEOUT_SECONDS", "20"))
CONCENTRATION_THRESHOLD_PCT = 40.0
FX_EXPOSURE_NOTABLE_PCT = 20.0
CASH_DRAG_NOTABLE_PCT = 10.0


class Holding(BaseModel):
    symbol: str
    currency: str
    marketValue: float
    costBasis: float


class PortfolioSummary(BaseModel):
    """Aggregates only. No transaction id, no txn date, no raw ledger row ever appears here —
    that is enforced by this shape simply not having fields for them."""

    baseCurrency: str
    totalMarketValue: float
    cashBalance: float = 0.0
    holdings: List[Holding] = Field(default_factory=list)


class InsightsRequest(BaseModel):
    portfolioSummary: PortfolioSummary
    horizon: str = "1M"
    tone: str = "concise"


class Highlight(BaseModel):
    type: str
    severity: str
    message: str


class InsightsResponse(BaseModel):
    summary: str
    highlights: List[Highlight]
    engine: str  # "LLM" | "RULE_BASED"


def _round1(value: float) -> float:
    return round(value, 1)


def _holding_weight_pct(holding: Holding, total_value: float) -> float:
    if total_value <= 0:
        return 0.0
    return (holding.marketValue / total_value) * 100.0


def _concentration_highlights(holdings: List[Holding], total_value: float) -> List[Highlight]:
    highlights = []
    for holding in holdings:
        weight = _holding_weight_pct(holding, total_value)
        if weight > CONCENTRATION_THRESHOLD_PCT:
            severity = "HIGH" if weight > 60.0 else "MEDIUM"
            highlights.append(Highlight(
                type="CONCENTRATION",
                severity=severity,
                message=f"{holding.symbol} is {_round1(weight)}% of market value.",
            ))
    return highlights


def _fx_exposure_highlight(summary: PortfolioSummary) -> Optional[Highlight]:
    total = summary.totalMarketValue
    if total <= 0:
        return None
    non_base_value = sum(h.marketValue for h in summary.holdings if h.currency != summary.baseCurrency)
    pct = (non_base_value / total) * 100.0
    if pct <= 0:
        return None
    severity = "HIGH" if pct > 60.0 else ("MEDIUM" if pct >= FX_EXPOSURE_NOTABLE_PCT else "LOW")
    return Highlight(
        type="FX_EXPOSURE",
        severity=severity,
        message=f"{_round1(pct)}% of holdings are non-{summary.baseCurrency}.",
    )


def _performance_highlights(holdings: List[Holding]) -> List[Highlight]:
    priced = [h for h in holdings if h.costBasis > 0]
    if not priced:
        return []

    def pnl_pct(h: Holding) -> float:
        return ((h.marketValue - h.costBasis) / h.costBasis) * 100.0

    best = max(priced, key=pnl_pct)
    worst = min(priced, key=pnl_pct)
    highlights = [Highlight(
        type="PERFORMANCE",
        severity="LOW",
        message=f"Best performer: {best.symbol} ({_round1(pnl_pct(best)):+}%).",
    )]
    if worst.symbol != best.symbol:
        highlights.append(Highlight(
            type="PERFORMANCE",
            severity="LOW",
            message=f"Worst performer: {worst.symbol} ({_round1(pnl_pct(worst)):+}%).",
        ))
    return highlights


def _cash_drag_highlight(summary: PortfolioSummary) -> Optional[Highlight]:
    total = summary.totalMarketValue + summary.cashBalance
    if total <= 0:
        return None
    pct = (summary.cashBalance / total) * 100.0
    if pct < CASH_DRAG_NOTABLE_PCT:
        return None
    severity = "HIGH" if pct > 30.0 else "MEDIUM"
    return Highlight(
        type="CASH_DRAG",
        severity=severity,
        message=f"{_round1(pct)}% of the portfolio is sitting in cash.",
    )


def generate_rule_based(request: InsightsRequest) -> InsightsResponse:
    """Deterministic, pure Python, no network — the fallback that must always work, built
    before the LLM path (D5-B1)."""
    summary = request.portfolioSummary
    highlights: List[Highlight] = []
    highlights.extend(_concentration_highlights(summary.holdings, summary.totalMarketValue))
    fx = _fx_exposure_highlight(summary)
    if fx is not None:
        highlights.append(fx)
    highlights.extend(_performance_highlights(summary.holdings))
    cash_drag = _cash_drag_highlight(summary)
    if cash_drag is not None:
        highlights.append(cash_drag)

    if highlights:
        summary_text = " ".join(h.message for h in highlights)
    else:
        summary_text = "No notable concentration, FX exposure, performance outlier or cash drag detected."

    return InsightsResponse(summary=summary_text, highlights=highlights, engine="RULE_BASED")


def _llm_api_key() -> str:
    return os.environ.get("INSIGHTS_LLM_API_KEY", "").strip()


def _llm_api_url() -> str:
    return os.environ.get("INSIGHTS_LLM_API_URL", "https://api.openai.com/v1/chat/completions").strip()


SYSTEM_PROMPT = """\
You write one short plain-text paragraph summarising a portfolio, for display inside a small \
card in a web dashboard.

FORMAT — the card renders your reply as raw text, so any formatting character appears \
literally on screen as itself:
- Plain prose only. No Markdown whatsoever.
- No headings, no tables, no bullet or numbered lists, no bold or italics, no backticks, \
no horizontal rules, no line breaks.
- 2 to 4 sentences, one paragraph, never more.

CONTENT — every number you need is supplied. You are rewriting verified figures into a \
sentence, not analysing raw data:
- Use ONLY the figures given. Never compute a new percentage, total or return of your own.
- Never restate the full holdings list; name at most the two or three that matter.
- Describe only what the figures say. Do not infer margin, leverage, borrowing, strategy, \
intent or risk appetite from them. A negative cash balance means the recorded cash is \
negative and nothing more.
- No investment advice, no recommendation, no prediction.
- If the figures show nothing notable, say so plainly in one sentence.
"""


def _build_llm_prompt(request: InsightsRequest, facts: List[str]) -> str:
    """
    The model is handed conclusions, not raw material.

    Percentages and rankings are computed by `generate_rule_based` in Python and passed in as
    `facts`; the model's only job is to turn them into a readable sentence. Asking it to derive
    figures from market values and cost bases — which is what the first version of this prompt
    did — is asking a language model to do arithmetic, and it produced confident, wrong
    percentages. Numbers come from Python; only the wording comes from here.
    """
    summary = request.portfolioSummary
    # Aggregates only — no transaction id, date or raw ledger row is ever available to put
    # here, because InsightsRequest/PortfolioSummary simply have no such fields.
    fact_lines = "\n".join(f"- {fact}" for fact in facts) or "- Nothing notable was detected."
    return (
        f"Tone: {request.tone}. Period: {request.horizon}.\n"
        f"Base currency: {summary.baseCurrency}.\n"
        f"Total market value: {summary.totalMarketValue}.\n"
        f"Cash balance: {summary.cashBalance}.\n"
        f"Number of holdings: {len(summary.holdings)}.\n"
        f"Verified findings, already calculated — use these figures exactly as written:\n"
        f"{fact_lines}\n\n"
        f"Write the paragraph."
    )


# A ceiling, not a target. The prompt asks for 2-4 sentences; this is what happens when the
# model ignores that. Cutting at a sentence boundary keeps the card readable rather than
# ending mid-word.
MAX_SUMMARY_CHARS = 700

# A floor against empty-ish junk, deliberately low. Length is a poor fragment detector: a
# terse but complete sentence is fine, and a truncated one can be long. The real test is
# whether it ends like a sentence — see `_is_usable`.
MIN_SUMMARY_CHARS = 20

# Generous on purpose. Thinking models — every Gemini 3 model, and 2.5 by default — spend this
# budget on internal reasoning BEFORE emitting a single visible character, and those tokens
# count against the same limit. A 220 budget produced literally "Over the 1M period, the": the
# reasoning consumed it and the answer was cut mid-clause. This must cover reasoning AND the
# paragraph, so it is sized for the former.
LLM_MAX_TOKENS = int(os.environ.get("INSIGHTS_LLM_MAX_TOKENS", "2000"))

# "minimal" | "low" | "medium" | "high", or "none" on Gemini 2.5 only — reasoning cannot be
# switched off on Gemini 3 at all, only turned down. Empty means "send nothing", which is the
# right default because OpenAI's own non-reasoning models reject the parameter outright.
LLM_REASONING_EFFORT = os.environ.get("INSIGHTS_LLM_REASONING_EFFORT", "").strip()

# Headings and rules are matched at a TOKEN boundary, not a line start. A model that emits its
# Markdown on one long line — which is exactly what happened in practice, "... : --- ###
# Executive Overview * ..." — leaves `^`-anchored patterns with nothing to match, and the `#`
# and `---` sail through to the card. `(?<!\S)` means "start of string, or after whitespace",
# which covers line starts as well since a newline is whitespace.
_TABLE_LINE = re.compile(r"^\s*\|.*$", re.MULTILINE)
_TABLE_DIVIDER = re.compile(r"^\s*[:\-\s|]{4,}$", re.MULTILINE)
# 3+ of - * _ standing alone as their own token. Bounded on both sides so ordinary hyphens
# survive: "1-month" and "-15,625.22" have no run of three, and "***bold***" is followed
# immediately by a letter, so it falls to _EMPHASIS instead.
_RULE = re.compile(r"(?<!\S)[-*_]{3,}(?!\S)")
_HEADING = re.compile(r"(?<!\S)#{1,6}\s*")
_LIST_MARKER = re.compile(r"(?<!\S)(?:[-*+]|\d{1,2}[.)])\s+")
_EMPHASIS = re.compile(r"(\*{1,3}|_{2,3}|`+)")
# Any pipe still standing was a table cell the line-based pass could not see. Prose has no use
# for one, so it goes.
_STRAY_PIPE = re.compile(r"\s*\|\s*")
_WHITESPACE = re.compile(r"\s+")


def to_plain_text(text: str, max_chars: int = MAX_SUMMARY_CHARS) -> str:
    """
    Force whatever the model returned into the single plain paragraph the card can render.

    This is deliberately belt-and-braces with the system prompt. Format instructions are a
    request that a model is free to ignore, and this one demonstrably did — it returned
    headings, a five-row table and bold markers, all of which the UI painted on screen as
    literal `#`, `|` and `**`. The prompt reduces how often that happens; this function is what
    makes it not matter when it does.
    """
    if not text:
        return ""

    cleaned = _TABLE_LINE.sub(" ", text)
    cleaned = _TABLE_DIVIDER.sub(" ", cleaned)
    # Rules before emphasis: _EMPHASIS eats asterisk runs, which would turn a "***" rule into
    # an invisible empty string and hide it from _RULE.
    cleaned = _RULE.sub(" ", cleaned)
    cleaned = _HEADING.sub("", cleaned)
    cleaned = _LIST_MARKER.sub("", cleaned)
    cleaned = _EMPHASIS.sub("", cleaned)
    cleaned = _STRAY_PIPE.sub(" ", cleaned)
    cleaned = _WHITESPACE.sub(" ", cleaned).strip()

    if len(cleaned) <= max_chars:
        return cleaned

    # Prefer the last sentence that fits; fall back to a word boundary if there isn't one.
    window = cleaned[:max_chars]
    cut = max(window.rfind(". "), window.rfind("! "), window.rfind("? "))
    if cut > max_chars // 2:
        return window[: cut + 1].strip()
    space = window.rfind(" ")
    return (window[:space] if space > 0 else window).strip() + "…"


def try_llm(request: InsightsRequest) -> Optional[InsightsResponse]:
    """Returns None on ANY failure (no key, timeout, HTTP error, malformed response) — the
    caller always falls back to the rule-based path, and never raises."""
    api_key = _llm_api_key()
    if not api_key:
        return None

    # Computed first, and deliberately so: its highlights are both the factual basis handed to
    # the model and the fallback if the call fails. The model never sees raw market values to
    # do sums on.
    rule_based = generate_rule_based(request)
    facts = [h.message for h in rule_based.highlights]

    payload = {
        "model": os.environ.get("INSIGHTS_LLM_MODEL", "gpt-4o-mini"),
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": _build_llm_prompt(request, facts)},
        ],
        # Must cover internal reasoning as well as the visible answer — see LLM_MAX_TOKENS.
        "max_tokens": LLM_MAX_TOKENS,
        # Formatting-constrained rewriting, not creative writing. Low temperature keeps the
        # shape stable between refreshes of the same card.
        "temperature": 0.3,
    }
    # Only sent when configured: OpenAI's non-reasoning models reject an unknown parameter
    # outright, so an unconditional one would break the default provider to help another.
    if LLM_REASONING_EFFORT:
        payload["reasoning_effort"] = LLM_REASONING_EFFORT

    try:
        response = httpx.post(
            _llm_api_url(),
            headers={"Authorization": f"Bearer {api_key}"},
            json=payload,
            timeout=LLM_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        body = response.json()
        choice = body["choices"][0]
        finish_reason = choice.get("finish_reason")

        # `length` means the budget ran out mid-thought, so whatever came back is a fragment —
        # famously "Over the 1M period, the". Rendering half a sentence is worse than rendering
        # a complete deterministic one, so this counts as a failed attempt, not a result.
        if finish_reason == "length":
            log.warning(
                "LLM reply truncated (finish_reason=length, max_tokens=%s); falling back to "
                "rule-based. Raise INSIGHTS_LLM_MAX_TOKENS, or lower "
                "INSIGHTS_LLM_REASONING_EFFORT so less of the budget goes to thinking.",
                LLM_MAX_TOKENS,
            )
            return None

        text = to_plain_text(choice["message"]["content"])
        if not _is_usable(text):
            log.warning(
                "LLM reply unusable (%d chars, finish_reason=%s); falling back to rule-based.",
                len(text), finish_reason,
            )
            return None
        return InsightsResponse(summary=text, highlights=rule_based.highlights, engine="LLM")
    except Exception as exc:
        # Network error, timeout, non-2xx, unexpected JSON shape — all treated the same way:
        # fall back, never propagate. This is the "the whole feature degrades before it fails"
        # rule from day-5-dev-B.md, one layer up from the Java client's own fallback.
        log.warning("LLM call failed (%s: %s); falling back to rule-based.",
                    type(exc).__name__, exc)
        return None


# Sentence-ending punctuation, allowing a trailing quote or bracket to close after it.
_SENTENCE_END = re.compile(r"[.!?…][\"')\]]*$")


def _is_usable(text: str) -> bool:
    """
    A summary has to be a sentence, not the beginning of one.

    The discriminating test is the ending, not the length. "Over the 1M period, the" is 23
    characters and obviously broken; "Nothing notable to report." is 26 and perfectly good. A
    length threshold cannot separate those, and one set high enough to catch the first would
    throw away the second.
    """
    return bool(text) and len(text) >= MIN_SUMMARY_CHARS and bool(_SENTENCE_END.search(text))


@app.post("/insights", response_model=InsightsResponse)
def insights(request: InsightsRequest) -> InsightsResponse:
    llm_result = try_llm(request)
    if llm_result is not None:
        return llm_result
    return generate_rule_based(request)


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}
