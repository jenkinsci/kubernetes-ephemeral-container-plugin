package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;

/**
 * Container image reference. Image references parsed by this class are always normalized:
 * <ul>
 *     <li>If no domain component, "{@code docker.io}" will be added</li>
 *     <li>If domain is "{@code docker.io}" and no repo component "{@code library/}" will be added</li>
 *     <li>If no tag or digest component, "{@code latest}" tag will be added</li>
 * </ul>
 * <pre>
 *     maven           -> docker.io/library/maven:latest
 *     docker.io/maven -> docker.io/library/maven:latest
 * </pre>
 */
public class ImageReference {

    // See https://regex101.com/r/eK9lPd/3
    private static final Pattern IMAGE_REF_PATTERN = Pattern.compile(
            "^(?<Name>(?<=^)(?:(?<Domain>(?:(?:localhost|[\\w-]+(?:\\.[\\w-]+)+)(?::\\d+)?)|\\w+:\\d+)/)?/?(?<Namespace>(?:(?:[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*)/)*)(?<Repo>[a-z0-9-]+))[:@]?(?<Reference>(?<=:)(?<Tag>\\w[\\w.-]{0,127})|(?<=@)(?<Digest>[A-Za-z][A-Za-z0-9]*(?:[-_+.][A-Za-z][A-Za-z0-9]*)*:[0-9A-Fa-f]{32,}))?");

    private final String domain;
    private final String path;
    private final String tag;
    private final String digest;
    private final String name;
    private final String reference;

    /**
     * Parse an image reference string. The parse reference will be normalized,
     * meaning if no registry/domain component is specified, it will be assumed
     * to be "{@code docker.io}".
     * <p>
     * This parse image references that are not technically valid. The may purpose
     * is to break it into component pieces so rules can be applied against them.
     * We are not concerned about strict limits as that will ultimately be the
     * responsibility of the cluster.
     *
     * @param reference reference string, not null
     * @return image reference or empty if invalid refence format
     */
    public static Optional<ImageReference> parse(@NonNull String reference) {
        String digest = null;
        // The regex doesn't handle references where both tag and digest are included.
        // This explicitly handles the digest component.
        String[] parts = StringUtils.split(reference, '@');
        if (parts.length == 2) {
            reference = parts[0];
            digest = parts[1];
        }
        Matcher matcher = IMAGE_REF_PATTERN.matcher(reference);
        if (matcher.matches()) {
            String domain = matcher.group("Domain");
            if (domain == null) {
                domain = "docker.io";
            }

            String name = matcher.group("Repo");
            String namespace = matcher.group("Namespace");
            if (StringUtils.isEmpty(namespace) && StringUtils.equals(domain, "docker.io")) {
                namespace = "library/";
            }

            if (!StringUtils.isEmpty(namespace)) {
                name = namespace + name;
            }

            String tag = matcher.group("Tag");
            if (StringUtils.isEmpty(tag) && StringUtils.isEmpty(digest)) {
                tag = "latest";
            }

            return Optional.of(new ImageReference(domain, name, tag, digest));
        }

        return Optional.empty();
    }

    private ImageReference(
            @NonNull String domain, @NonNull String path, @Nullable String tag, @Nullable String digest) {
        this.domain = domain;
        this.path = path;
        this.tag = tag;
        this.digest = digest;
        this.name = domain + "/" + path;
        String ref = this.name;
        if (tag != null) {
            ref += ":" + tag;
        }

        if (digest != null) {
            ref += "@" + digest;
        }

        this.reference = ref;
    }

    /**
     * Image registry domain.
     * @return registry domain, not {@code null}
     */
    @NonNull
    public String getDomain() {
        return domain;
    }

    /**
     * Image path component. Does not include the domain component.
     * @return path, not {@code null}
     */
    @NonNull
    public String getPath() {
        return path;
    }

    /**
     * Image name (domain and path). Does not include tag or digest.
     * @return name, not {@code null}
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Image tag name.
     * @return tag name or {@code null}
     */
    @Nullable
    public String getTag() {
        return tag;
    }

    /**
     * Image digest.
     * @return image digest or {@code null}
     */
    @Nullable
    public String getDigest() {
        return digest;
    }

    /**
     * Get full image reference string (domain + path + tag/digest).
     * @return full image reference string
     */
    @NonNull
    public String getReference() {
        return reference;
    }

    @Override
    public String toString() {
        return reference;
    }
}
