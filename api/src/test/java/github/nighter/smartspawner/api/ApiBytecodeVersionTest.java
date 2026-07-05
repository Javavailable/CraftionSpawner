package github.nighter.smartspawner.api;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the public API module is compiled to Java 21 bytecode (class-file major version 65) so it
 * can be consumed by Java 21 toolchains (e.g. CraftionFarmer). This reads the compiled class files
 * from the api module's own runtime classpath.
 */
class ApiBytecodeVersionTest {

    private static final int JAVA_21_MAJOR = 65;

    @Test
    void publicApiClassesAreCompiledForJava21() throws Exception {
        assertMajorVersion("/github/nighter/smartspawner/api/SmartSpawnerAPI.class", JAVA_21_MAJOR);
        assertMajorVersion("/github/nighter/smartspawner/api/output/SpawnerOutputRouter.class", JAVA_21_MAJOR);
        assertMajorVersion("/github/nighter/smartspawner/api/output/SpawnerOutputContext.class", JAVA_21_MAJOR);
        assertMajorVersion("/github/nighter/smartspawner/api/output/SpawnerOutputResult.class", JAVA_21_MAJOR);
        assertMajorVersion("/github/nighter/smartspawner/api/output/SpawnerOutputRouterRegistry.class", JAVA_21_MAJOR);
    }

    private void assertMajorVersion(String classResource, int expectedMajor) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(classResource)) {
            assertNotNull(in, "Missing class resource: " + classResource);
            DataInputStream data = new DataInputStream(in);
            int magic = data.readInt();
            assertEquals(0xCAFEBABE, magic, "Not a class file: " + classResource);
            data.readUnsignedShort(); // minor version
            int major = data.readUnsignedShort();
            assertEquals(expectedMajor, major, "Unexpected bytecode major version for " + classResource);
        }
    }
}
