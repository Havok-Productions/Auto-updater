package dev.autoupdater;

public final class RegressionTestSuite {
    private RegressionTestSuite() {
    }

    public static void main(String[] args) throws Exception {
        UpgradeSmokeTestRegressionTest.main(args);
        UpgradePreparationRegressionTest.main(args);
        DiscoveryProofFlowRegressionTest.main(args);
        ServerRollbackLockRegressionTest.main(args);
        HostedFallbackGitFreshnessRegressionTest.main(args);
        SourceOriginCleanupPersistenceRegressionTest.main(args);
        UnverifiedDiscoveredSourceRegressionTest.main(args);
        StartupRollbackPolicyRegressionTest.main(args);
        Utf8BomConfigRegressionTest.main(args);
        ThirdInvariantPatchRegressionTest.main(args);
        ParserPolicyRegressionTest.main(args);
        PreferredForkDiscoveryRegressionTest.main(args);
    }
}
