# Upstream starting points

| Component | Version used | Source revision checked 2026-09-16 |
| --- | --- | --- |
| [Timefold quickstarts](https://github.com/TimefoldAI/timefold-quickstarts/tree/stable/getting-started/spring-boot-integration) | Spring Boot 4.1.1 and Timefold 2.6.0 configuration | `b9abb3bcd417d51cbd972a69744ba9fc81173b7f` (`stable`) |
| [GraphHopper](https://github.com/graphhopper/graphhopper/tree/11.0) | 11.0 | `69e50f6e2cfaf0a8e69752df9953ee5f1ac276a4` |
| [Nominatim Docker](https://github.com/mediagis/nominatim-docker) | Docker image 5.3 | `44bb833a05eaf714e53f471cd8f95d3b4b8acd59` (repository HEAD; image digest still needs pinning) |
| [Planetiler](https://github.com/onthegomap/planetiler/tree/v0.9.0) | 0.9.0 | `2c91725f6d048fd60b02d3e7c29bb88838451048` |
| [go-pmtiles](https://github.com/protomaps/go-pmtiles/tree/v1.22.1) | v1.22.1 | `f1c24e64f3085877d57c8e0f07233e0a3ef25a99` |
| [Apache Maven Wrapper](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4) | Wrapper 3.3.4, Maven distribution 3.9.16 | `c82bcee2275b9ee2afd0fab17fdec05e1c6f67aa` |

The routing code follows GraphHopper's [Java car profile example](https://github.com/graphhopper/graphhopper/blob/11.0/example/src/main/java/com/graphhopper/example/RoutingExample.java). No upstream source code was copied into the services.
