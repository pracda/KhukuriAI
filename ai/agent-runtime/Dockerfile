FROM python:3.12-slim
WORKDIR /app

COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

EXPOSE 8184
CMD ["uvicorn", "khukuri_agent.main:app", "--host", "0.0.0.0", "--port", "8184"]
