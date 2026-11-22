package com.ontologywiki.query;

import com.ontologywiki.query.controller.DataQueryController;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class QueryArchitectureTest {
    @Test
    public void controllerDependsOnlyOnApplicationServiceInterfaces() {
        for (Field field : DataQueryController.class.getDeclaredFields()) {
            assertTrue(field.getName(), field.getType().isInterface());
            assertEquals("com.ontologywiki.query.service", field.getType().getPackage().getName());
        }
    }

    @Test
    public void servicesCannotOpenConnectionsOrContainPersistenceSql() throws IOException {
        Path root = modulePath("src/main/java/com/ontologywiki/query");
        assertTrue(Files.isDirectory(root));
        for (String folder : new String[]{"controller", "service", "repository"}) {
            for (Path file : files(root.resolve(folder))) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse(file.toString(), source.matches("(?s).*(java\\.sql\\.|javax\\.sql\\.|JdbcTemplate|DriverManager|PreparedStatement|createStatement\\().*"));
                assertFalse(file.toString(), source.matches("(?si).*\"\\s*(select\\s+.+?\\s+from|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from).*"));
            }
        }
    }

    @Test
    public void systemMappersUseBoundParametersInsteadOfSqlSubstitution() throws IOException {
        List<Path> resources = files(modulePath("src/main/resources/mapper/query"));
        assertEquals(3, resources.size());
        for (Path file : resources) {
            String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertFalse(file.toString(), xml.contains("${"));
            assertTrue(file.toString(), xml.contains("#{"));
        }
    }

    private Path modulePath(String relativePath) {
        // Surefire supplies the module base directory even when tests share Maven's JVM.
        return Paths.get(System.getProperty("basedir", ".")).resolve(relativePath);
    }

    private List<Path> files(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }
}
