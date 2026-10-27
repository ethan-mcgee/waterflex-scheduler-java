# Upstream starting points

The upstream projects this repository started from, with the version used here and the source revision checked:

| Component | Version used here | Source revision checked 2026-10-22 |
| --- | --- | --- |
| [Timefold quickstarts](https://github.com/TimefoldAI/timefold-quickstarts/tree/stable/getting-started/spring-boot-integration) | Starting configuration. This repository now runs Spring Boot 4.1.2 and Timefold Solver 2.7.1 (`timefold.version` in `pom.xml`) | `76993a35123a127f9b630a8f7a37a1fbe76a24b0` (`stable`, which still uses Spring Boot 4.1.1) |
| [GraphHopper](https://github.com/graphhopper/graphhopper/tree/11.1) | 11.1 (`routing-service/pom.xml`) | `ebb578f4e94db72e82f2f8d3bc69417758e16e1c` (tag `11.1`) |
| [Nominatim Docker](https://github.com/mediagis/nominatim-docker) | Docker image 5.3 | `44bb833a05eaf714e53f471cd8f95d3b4b8acd59` (checked 2026-09-16; the image digest still needs pinning) |
| [Planetiler](https://github.com/onthegomap/planetiler/tree/v0.9.0) | 0.9.0 | `2c91725f6d048fd60b02d3e7c29bb88838451048` (checked 2026-09-16) |
| [go-pmtiles](https://github.com/protomaps/go-pmtiles/tree/v1.22.1) | v1.22.1 | `f1c24e64f3085877d57c8e0f07233e0a3ef25a99` (checked 2026-09-16) |
| [Apache Maven Wrapper](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4) | Wrapper 3.3.4, Maven distribution 3.10.0 (`.mvn/wrapper/maven-wrapper.properties`) | `c82bcee2275b9ee2afd0fab17fdec05e1c6f67aa` (tag `maven-wrapper-3.3.4`) |

The routing code follows GraphHopper's [Java car profile example](https://github.com/graphhopper/graphhopper/blob/11.1/example/src/main/java/com/graphhopper/example/RoutingExample.java). No upstream source code was copied into the services.
