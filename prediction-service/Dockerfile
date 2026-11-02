FROM python:3.13.18-slim AS stubs
WORKDIR /repo
COPY prediction-contracts/src/main/proto prediction-contracts/src/main/proto
COPY stop-events-contracts/src/main/proto stop-events-contracts/src/main/proto
COPY prediction-service/requirements.txt prediction-service/requirements-dev.txt prediction-service/generate_stubs.py prediction-service/
COPY prediction-service/src prediction-service/src
RUN grep '^grpcio-tools==' prediction-service/requirements-dev.txt > /tmp/tools.txt \
    && pip install --no-cache-dir --requirement /tmp/tools.txt \
    && python prediction-service/generate_stubs.py

FROM python:3.13.18-slim AS dependencies
COPY prediction-service/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --prefix=/install --requirement /tmp/requirements.txt

FROM python:3.13.18-slim
RUN apt-get update && apt-get install --yes --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && install -d -o app -g app /data
COPY --from=dependencies /install /usr/local
WORKDIR /app
COPY --from=stubs /repo/prediction-service/src src
COPY prediction-service/bundle bundle
ENV PYTHONPATH=/app/src PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
USER app
ENTRYPOINT ["python", "-m", "krktransit_prediction"]
