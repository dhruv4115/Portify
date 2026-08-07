# D5-B1 — insights service image. Keyless by default: INSIGHTS_LLM_API_KEY absent means the
# service still starts and only ever serves the rule-based path (main.py).
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# Non-root, same reasoning as docker/Dockerfile (D4-B1) — noticed at a bank.
RUN useradd --system --create-home app
USER app

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
