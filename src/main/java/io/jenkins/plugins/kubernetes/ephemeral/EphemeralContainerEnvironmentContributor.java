package io.jenkins.plugins.kubernetes.ephemeral;

import hudson.Extension;
import hudson.model.EnvironmentContributor;

/**
 * This extension doesn't actually add any env vars. It is a placeholder to contribute to
 * <code>/env-vars.html</code>. The env vars are added via {@link EphemeralContainerKubernetesComputer}.
 * @see EphemeralContainerKubernetesComputer
 */
@Extension
public class EphemeralContainerEnvironmentContributor extends EnvironmentContributor {}
