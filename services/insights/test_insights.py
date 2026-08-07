"""
D5-B1 — test_insights.py

No test here calls a real LLM: the LLM path is exercised only via monkeypatched env vars and a
monkeypatched `httpx.post`, never a live network call.
"""

import importlib

import pytest
from fastapi.testclient import TestClient

import main as insights_main


@pytest.fixture()
def client(monkeypatch):
    # No key by default — every test starts from the "no key" baseline unless it opts in.
    monkeypatch.delenv("INSIGHTS_LLM_API_KEY", raising=False)
    importlib.reload(insights_main)
    return TestClient(insights_main.app)


def _concentrated_payload():
    return {
        "portfolioSummary": {
            "baseCurrency": "INR",
            "totalMarketValue": 100000.0,
            "cashBalance": 5000.0,
            "holdings": [
                {"symbol": "AAPL", "currency": "USD", "marketValue": 57000.0, "costBasis": 50000.0},
                {"symbol": "RELIANCE", "currency": "INR", "marketValue": 20000.0, "costBasis": 25000.0},
            ],
        },
        "horizon": "1M",
        "tone": "concise",
    }


def test_rule_based_output_is_deterministic(client):
    payload = _concentrated_payload()

    first = client.post("/insights", json=payload).json()
    second = client.post("/insights", json=payload).json()

    assert first == second
    assert first["engine"] == "RULE_BASED"


def test_no_key_means_rule_based(client, monkeypatch):
    monkeypatch.delenv("INSIGHTS_LLM_API_KEY", raising=False)

    response = client.post("/insights", json=_concentrated_payload())

    assert response.status_code == 200
    assert response.json()["engine"] == "RULE_BASED"


def test_llm_timeout_falls_back_to_rule_based(monkeypatch):
    monkeypatch.setenv("INSIGHTS_LLM_API_KEY", "test-key-not-real")
    importlib.reload(insights_main)

    def raise_timeout(*args, **kwargs):
        raise insights_main.httpx.TimeoutException("simulated timeout")

    monkeypatch.setattr(insights_main.httpx, "post", raise_timeout)
    client = TestClient(insights_main.app)

    response = client.post("/insights", json=_concentrated_payload())

    assert response.status_code == 200
    assert response.json()["engine"] == "RULE_BASED"


def test_llm_success_reports_llm_engine(monkeypatch):
    monkeypatch.setenv("INSIGHTS_LLM_API_KEY", "test-key-not-real")
    importlib.reload(insights_main)

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "A generated summary."}}]}

    captured_calls = []

    def fake_post(url, headers=None, json=None, timeout=None):
        captured_calls.append({"url": url, "headers": headers, "json": json, "timeout": timeout})
        return FakeResponse()

    monkeypatch.setattr(insights_main.httpx, "post", fake_post)
    client = TestClient(insights_main.app)

    response = client.post("/insights", json=_concentrated_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["engine"] == "LLM"
    assert body["summary"] == "A generated summary."
    assert len(captured_calls) == 1
    assert captured_calls[0]["timeout"] == insights_main.LLM_TIMEOUT_SECONDS


def test_concentration_highlight_fires_above_40_percent(client):
    response = client.post("/insights", json=_concentrated_payload())

    highlights = response.json()["highlights"]
    concentration = [h for h in highlights if h["type"] == "CONCENTRATION"]
    assert len(concentration) == 1
    assert "AAPL" in concentration[0]["message"]


def test_concentration_highlight_does_not_fire_at_or_below_40_percent(client):
    payload = {
        "portfolioSummary": {
            "baseCurrency": "INR",
            "totalMarketValue": 100000.0,
            "cashBalance": 0.0,
            "holdings": [
                {"symbol": "AAPL", "currency": "USD", "marketValue": 40000.0, "costBasis": 40000.0},
                {"symbol": "RELIANCE", "currency": "INR", "marketValue": 35000.0, "costBasis": 35000.0},
            ],
        },
        "horizon": "1M",
        "tone": "concise",
    }

    response = client.post("/insights", json=payload)

    concentration = [h for h in response.json()["highlights"] if h["type"] == "CONCENTRATION"]
    assert concentration == []


def test_fx_exposure_highlight_reports_non_base_currency_share(client):
    response = client.post("/insights", json=_concentrated_payload())

    fx = [h for h in response.json()["highlights"] if h["type"] == "FX_EXPOSURE"]
    assert len(fx) == 1
    assert "57" in fx[0]["message"]


def test_cash_drag_highlight_fires_above_threshold(client):
    payload = {
        "portfolioSummary": {
            "baseCurrency": "INR",
            "totalMarketValue": 70000.0,
            "cashBalance": 30000.0,
            "holdings": [
                {"symbol": "RELIANCE", "currency": "INR", "marketValue": 70000.0, "costBasis": 65000.0},
            ],
        },
        "horizon": "1M",
        "tone": "concise",
    }

    response = client.post("/insights", json=payload)

    cash_drag = [h for h in response.json()["highlights"] if h["type"] == "CASH_DRAG"]
    assert len(cash_drag) == 1


def test_best_and_worst_performer_highlights(client):
    response = client.post("/insights", json=_concentrated_payload())

    performance = [h for h in response.json()["highlights"] if h["type"] == "PERFORMANCE"]
    assert any("Best performer: AAPL" in h["message"] for h in performance)
    assert any("Worst performer: RELIANCE" in h["message"] for h in performance)


def test_upstream_payload_contains_no_transaction_level_detail(monkeypatch):
    monkeypatch.setenv("INSIGHTS_LLM_API_KEY", "test-key-not-real")
    importlib.reload(insights_main)

    captured_calls = []

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "ok"}}]}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured_calls.append(json)
        return FakeResponse()

    monkeypatch.setattr(insights_main.httpx, "post", fake_post)
    client = TestClient(insights_main.app)

    client.post("/insights", json=_concentrated_payload())

    assert len(captured_calls) == 1
    sent_body = str(captured_calls[0])
    # No transaction id, no txn date/executedAt, no raw ledger fields anywhere in what was sent.
    for forbidden in ("txnId", "transactionId", "executedAt", "quantity", "fees"):
        assert forbidden not in sent_body


def test_health_endpoint(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


# --- summary formatting ------------------------------------------------------------------
#
# The card renders `summary` as a plain text node, so any Markdown the model emits is painted
# on screen as literal `#`, `|` and `**`. The system prompt asks for plain prose; these tests
# cover the case where it is ignored, which is not hypothetical — REAL_MODEL_OUTPUT below is a
# verbatim reply this service actually returned.

REAL_MODEL_OUTPUT = (
    "Here is a concise 1-month portfolio summary (all figures in **INR**): "
    "--- ### **Executive Overview** * **Total Market Value:** Rs4,32,933.41 "
    "* **Unrealized Gain / Loss:** **-Rs15,625.22 (-3.48%)** --- ### **Holdings**\n"
    "| Ticker | Currency | Market Value |\n"
    "| :--- | :---: | :---: |\n"
    "| **AGG** | USD | Rs2,13,022.19 |\n"
    "--- ### **Key Highlights**\n"
    "1. **Top Performer:** **SAP** gained **+256.83%**.\n"
    "2. **Leverage Note:** Negative cash reflects a margin-funded position.\n"
)


def test_plain_text_strips_markdown_the_card_cannot_render():
    out = insights_main.to_plain_text(REAL_MODEL_OUTPUT)

    for artefact in ("#", "|", "**", "---", "\n"):
        assert artefact not in out, f"{artefact!r} would render literally in the card"


def test_plain_text_keeps_ordinary_hyphens_and_negative_numbers():
    """The rule-stripper must not eat "1-month" or "-15,625.22" while removing "---"."""
    out = insights_main.to_plain_text("A 1-month view. --- Down -15,625.22 (-3.48%) so far.")

    assert "1-month" in out
    assert "-15,625.22" in out
    assert "-3.48%" in out
    assert "---" not in out


def test_plain_text_handles_markdown_emitted_inline_on_one_line():
    """
    The bug that shipped first: `^`-anchored patterns matched nothing when the model put its
    Markdown on a single line, so `###` and `---` reached the card untouched.
    """
    out = insights_main.to_plain_text("Summary: --- ### Overview * Value: 100 --- ### End")

    assert "#" not in out
    assert "---" not in out
    assert "Overview" in out


def test_plain_text_truncates_at_a_sentence_boundary():
    text = "One. " + ("Filler sentence here. " * 80)

    out = insights_main.to_plain_text(text)

    assert len(out) <= insights_main.MAX_SUMMARY_CHARS
    # Cut cleanly rather than mid-word.
    assert out.endswith(".") or out.endswith("…")


def test_plain_text_leaves_a_well_behaved_paragraph_alone():
    good = (
        "Your portfolio is up 10.2% against cost, driven mainly by AAPL. "
        "Currency is doing real work here: 90% of the book is priced outside your INR base."
    )

    assert insights_main.to_plain_text(good) == good


def test_llm_reply_is_sanitised_before_it_reaches_the_response(monkeypatch):
    """End to end: Markdown from the provider never survives into `summary`."""
    monkeypatch.setenv("INSIGHTS_LLM_API_KEY", "test-key-not-real")
    importlib.reload(insights_main)

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": REAL_MODEL_OUTPUT}}]}

    monkeypatch.setattr(insights_main.httpx, "post", lambda *a, **k: FakeResponse())
    client = TestClient(insights_main.app)

    body = client.post("/insights", json=_concentrated_payload()).json()

    assert body["engine"] == "LLM"
    for artefact in ("#", "|", "**", "---", "\n"):
        assert artefact not in body["summary"]


def _llm_client(monkeypatch, content, finish_reason="stop", capture=None):
    """A client whose provider returns exactly `content` with exactly `finish_reason`."""
    monkeypatch.setenv("INSIGHTS_LLM_API_KEY", "test-key-not-real")
    importlib.reload(insights_main)

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": content},
                                 "finish_reason": finish_reason}]}

    def fake_post(url, headers=None, json=None, timeout=None):
        if capture is not None:
            capture.update(json)
        return FakeResponse()

    monkeypatch.setattr(insights_main.httpx, "post", fake_post)
    return TestClient(insights_main.app)


def test_truncated_reply_falls_back_instead_of_rendering_a_fragment(monkeypatch):
    """
    The exact failure seen in the card: a thinking model spent the whole token budget on
    internal reasoning and the visible answer was cut mid-clause. Half a sentence is worse
    than a complete deterministic one, so this is a failed attempt, not a result.
    """
    client = _llm_client(monkeypatch, "Over the 1M period, the", finish_reason="length")

    body = client.post("/insights", json=_concentrated_payload()).json()

    assert body["engine"] == "RULE_BASED"
    assert "Over the 1M period, the" not in body["summary"]
    assert body["summary"]


def test_fragment_falls_back_even_when_the_provider_claims_it_finished(monkeypatch):
    """
    Belt and braces: an unterminated clause is a fragment whatever finish_reason says. Detected
    by how it ends, not how long it is.
    """
    client = _llm_client(monkeypatch, "Over the 1M period, the", finish_reason="stop")

    assert client.post("/insights", json=_concentrated_payload()).json()["engine"] == "RULE_BASED"


def test_a_terse_but_complete_sentence_is_accepted(monkeypatch):
    """
    The counter-case that rules out "just require it to be long". This is shorter than the
    fragment above and entirely valid.
    """
    client = _llm_client(monkeypatch, "Nothing notable to report.", finish_reason="stop")

    assert client.post("/insights", json=_concentrated_payload()).json()["engine"] == "LLM"


def test_token_budget_is_large_enough_to_cover_reasoning_tokens(monkeypatch):
    """
    Thinking models bill and count reasoning against the same ceiling as the answer, so this
    budget has to accommodate both. 220 left roughly five tokens for visible text.
    """
    captured = {}
    client = _llm_client(monkeypatch, "x" * 200, capture=captured)
    client.post("/insights", json=_concentrated_payload())

    assert captured["max_tokens"] >= 1000


def test_reasoning_effort_is_omitted_unless_configured(monkeypatch):
    """OpenAI's non-reasoning models reject an unknown parameter, so it must not be sent blind."""
    monkeypatch.delenv("INSIGHTS_LLM_REASONING_EFFORT", raising=False)
    captured = {}
    client = _llm_client(monkeypatch, "A perfectly adequate generated summary sentence here.",
                         capture=captured)
    client.post("/insights", json=_concentrated_payload())

    assert "reasoning_effort" not in captured


def test_reasoning_effort_is_forwarded_when_configured(monkeypatch):
    monkeypatch.setenv("INSIGHTS_LLM_REASONING_EFFORT", "minimal")
    captured = {}
    client = _llm_client(monkeypatch, "A perfectly adequate generated summary sentence here.",
                         capture=captured)
    client.post("/insights", json=_concentrated_payload())

    assert captured["reasoning_effort"] == "minimal"


def test_model_is_given_precomputed_facts_rather_than_raw_figures_to_divide(monkeypatch):
    """
    Percentages are computed in Python and handed over as text. Asking a language model to
    derive them from market values and cost bases is what produced confidently wrong returns.
    """
    monkeypatch.setenv("INSIGHTS_LLM_API_KEY", "test-key-not-real")
    importlib.reload(insights_main)

    captured = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "Fine."}}]}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured.update(json)
        return FakeResponse()

    monkeypatch.setattr(insights_main.httpx, "post", fake_post)
    client = TestClient(insights_main.app)
    client.post("/insights", json=_concentrated_payload())

    messages = captured["messages"]
    assert messages[0]["role"] == "system"
    # The rule-based engine's own concentration finding, verbatim, as ground truth.
    assert "57.0%" in messages[1]["content"]
    # The raw figures it could divide are NOT offered up for it to do arithmetic on.
    assert "costBasis" not in messages[1]["content"]
