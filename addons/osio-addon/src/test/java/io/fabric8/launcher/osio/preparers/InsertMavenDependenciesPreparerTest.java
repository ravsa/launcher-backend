package io.fabric8.launcher.osio.preparers;

import io.fabric8.launcher.base.maven.Maven;
import io.fabric8.launcher.booster.catalog.Booster;
import io.fabric8.launcher.booster.catalog.BoosterDataTransformer;
import io.fabric8.launcher.booster.catalog.LauncherConfiguration;
import io.fabric8.launcher.booster.catalog.rhoar.BoosterPredicates;
import io.fabric8.launcher.booster.catalog.rhoar.Mission;
import io.fabric8.launcher.booster.catalog.rhoar.RhoarBooster;
import io.fabric8.launcher.booster.catalog.rhoar.RhoarBoosterCatalogService;
import io.fabric8.launcher.booster.catalog.rhoar.Runtime;
import io.fabric8.launcher.booster.catalog.rhoar.Version;
import io.fabric8.launcher.osio.projectiles.context.OsioProjectileContext;

import java.nio.file.Path;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import io.fabric8.launcher.osio.providers.DependencyParamConverter;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.arquillian.smart.testing.rules.git.server.GitServer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

public class InsertMavenDependenciesPreparerTest {
    @ClassRule
    public static GitServer gitServer = GitServer.bundlesFromDirectory("repos/boosters")
            .usingPort(8765)
            .create();

    private static RhoarBoosterCatalogService boosterCatalogService;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();


    @BeforeClass
    public static void setUp() throws ExecutionException, InterruptedException {
        boosterCatalogService = buildDefaultCatalogService();
        boosterCatalogService.index().get();
    }

    private static RhoarBoosterCatalogService buildDefaultCatalogService() {
        if (boosterCatalogService == null) {
            RhoarBoosterCatalogService.Builder builder = new RhoarBoosterCatalogService.Builder();
            builder.catalogRepository(LauncherConfiguration.boosterCatalogRepositoryURI());
            builder.catalogRef(LauncherConfiguration.boosterCatalogRepositoryRef());
            builder.transformer(new TestRepoUrlFixer("http://localhost:8765"));
            boosterCatalogService = builder.build();
        }
        return boosterCatalogService;
    }

    @Test
    public void shouldAddDependencies() throws IOException, InterruptedException, URISyntaxException {
        // given
        final Optional<RhoarBooster> booster = boosterCatalogService.getBooster(
                BoosterPredicates.withMission(new Mission("rest-http"))
                        .and(BoosterPredicates.withRuntime(new Runtime("vert.x"))
                                .and(BoosterPredicates.withVersion(new Version("redhat")))));


        final RhoarBooster rhoarBooster = booster.get();
        final Path rootPath = temporaryFolder.getRoot().toPath();
        boosterCatalogService.copy(rhoarBooster, rootPath);

        final InsertMavenDependenciesPreparer insertMavenDependenciesPreparer = new InsertMavenDependenciesPreparer();

        // when
        final MyOsioProjectileContext myOsioProjectileContext = new MyOsioProjectileContext();
        insertMavenDependenciesPreparer.prepare(rootPath, rhoarBooster, myOsioProjectileContext);

        // then
        final Path pom = rootPath.resolve("pom.xml");
        final Model model = Maven.readModel(pom);
        for (Dependency dep : myOsioProjectileContext.getDependencies()) {
            assertThat(model.getDependencies().stream().anyMatch(d ->
                    d.getGroupId().equals(dep.getGroupId()) &&
                            d.getArtifactId().equals(dep.getArtifactId()) &&
                            d.getVersion().equals(dep.getVersion())
            )).isTrue();
        }
    }


    private static class MyOsioProjectileContext extends OsioProjectileContext {

        @Override
        public List<Dependency> getDependencies() {
            List<Dependency> deps = new ArrayList<>();
            String[] dependencies = {"io.vertx:vertx-sql-common:3.5.0", "io.vertx:vertx-jdbc-client:3.5.0"};
            DependencyParamConverter dependencyParamConverter = new DependencyParamConverter();
            for (String dependency : dependencies) {
                deps.add(dependencyParamConverter.fromString(dependency));
            }
            return deps;
        }
    }

    private static class TestRepoUrlFixer implements BoosterDataTransformer {
        private final String fixedUrl;

        TestRepoUrlFixer(String fixedUrl) {
            this.fixedUrl = fixedUrl;
        }

        @Override
        public Map<String, Object> transform(Map<String, Object> data) {
            String gitRepo = Booster.getDataValue(data, "source/git/url", null);
            if (gitRepo != null) {
                gitRepo = gitRepo.replace("https://github.com", fixedUrl);
                Booster.setDataValue(data, "source/git/url", gitRepo);
            }
            return data;
        }
    }
}
