package dev.waterflex.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The build this replica runs: the release tag and the commit its image was built from ({@code scheduler.release} and
 * {@code scheduler.revision}, which the image sets from its build arguments; infra/release-vm.sh passes them). A build
 * from a working tree names neither and reports {@code dev}. {@code /health} reports both and the start logs them, so an
 * operator can name the build behind any answer.
 */
@Component
public final class BuildInfo {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(BuildInfo.class));
    static final String UNRELEASED = "dev";

    private final String release;
    private final String revision;

    @Autowired
    public BuildInfo(@Value("${scheduler.release:dev}") String release, @Value("${scheduler.revision:dev}") String revision) {
        this.release = named(release);
        this.revision = named(revision);
        LOG.info("Scheduler release {} at commit {}", this.release, this.revision);
    }

    private static String named(String configured) {
        String value = configured.trim();
        return value.isEmpty() ? UNRELEASED : value;
    }

    /** The release tag (v2026.11.13.1), or dev. */
    public String release() { return release; }

    /** The commit the image was built from, or dev. */
    public String revision() { return revision; }
}
