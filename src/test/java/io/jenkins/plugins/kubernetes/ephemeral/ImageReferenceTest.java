package io.jenkins.plugins.kubernetes.ephemeral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ImageReferenceTest {

    @ParameterizedTest
    @CsvSource({
        "maven, docker.io, library/maven, latest, ",
        "docker.io/maven, docker.io, library/maven, latest, ",
        "docker.io/library/maven, docker.io, library/maven, latest, ",
        "docker.io/library/maven:3, docker.io, library/maven, 3, ",
        "docker.io/library/maven:@sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62, docker.io, library/maven, , sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62",
        "docker.io/library/maven:3@sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62, docker.io, library/maven, 3, sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62",
        "maven@sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62, docker.io, library/maven, , sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62",
        "example.com/maven, example.com, maven, latest, ",
        // invalid references
        "maven%dogs, , , , ",
    })
    void parse(String input, String domain, String path, String tag, String digest) {
        Optional<ImageReference> ref = ImageReference.parse(input);
        if (domain == null) {
            assertTrue(ref.isEmpty(), "expected empty response due to invalid format");
        } else {
            assertTrue(ref.isPresent());
            ImageReference r = ref.get();
            assertEquals(domain, r.getDomain());
            assertEquals(path, r.getPath());
            assertEquals(domain + "/" + path, r.getName());
            assertEquals(tag, r.getTag());
            assertEquals(digest, r.getDigest());
            assertEquals(r.toString(), r.getReference());
        }
    }
}
