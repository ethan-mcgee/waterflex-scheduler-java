FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl osmium-tool coreutils python3 && rm -rf /var/lib/apt/lists/*
COPY prepare.sh /usr/local/bin/prepare-map
COPY finalize.py /usr/local/bin/finalize-map.py
RUN chmod +x /usr/local/bin/prepare-map
ENTRYPOINT ["/usr/local/bin/prepare-map"]
