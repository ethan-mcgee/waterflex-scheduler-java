FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl osmium-tool coreutils python3 && rm -rf /var/lib/apt/lists/*
COPY prepare.sh /usr/local/bin/prepare-map
COPY finalize.py /usr/local/bin/finalize-map.py
COPY verify.py /usr/local/bin/verify-map.py
COPY filter_tiger.py /usr/local/bin/filter-tiger.py
COPY region.py /usr/local/bin/region-map.py
RUN chmod +x /usr/local/bin/prepare-map
ENTRYPOINT ["/usr/local/bin/prepare-map"]
