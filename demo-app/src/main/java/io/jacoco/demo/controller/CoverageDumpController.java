package io.jacoco.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes a single endpoint that flushes the in-memory JaCoCo execution data
 * to a {@code .exec} file on disk so it can be used to generate a JaCoCo HTML
 * report for comparison against the OTel metrics.
 *
 * <h3>Workflow</h3>
 * <ol>
 *   <li>Start the stack: {@code docker compose up --build}</li>
 *   <li>Generate traffic against {@code /api/greet}, {@code /api/calculate}, etc.</li>
 *   <li>Dump: {@code curl http://localhost:8080/api/coverage/dump}</li>
 *   <li>Generate HTML report: {@code mvn jacoco:report -f demo-app/pom.xml}</li>
 *   <li>Open {@code demo-app/target/site/jacoco/index.html} and compare the line/branch
 *       percentages with the {@code jacoco.coverage.*.ratio} metrics visible in Grafana.</li>
 * </ol>
 *
 * <p>The file is written to {@code /app/coverage/jacoco.exec} inside the container,
 * which is bind-mounted to {@code ./coverage/jacoco.exec} on the host via docker-compose.
 */
@RestController
@RequestMapping("/api/coverage")
public class CoverageDumpController {

    /** Path inside the container — bind-mounted to ./coverage/ on the host. */
    private static final String EXEC_PATH = "/app/coverage/jacoco.exec";

    /**
     * Reads the current execution data from the JaCoCo runtime MBean and writes it
     * to {@value #EXEC_PATH}.  Does NOT reset the in-memory counters, so the OTel
     * agent continues to see live data and subsequent dumps accumulate coverage.
     */
    @GetMapping("/dump")
    public ResponseEntity<Map<String, Object>> dump() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName mbeanName = new ObjectName("org.jacoco:type=Runtime");

            if (!server.isRegistered(mbeanName)) {
                body.put("status", "error");
                body.put("message",
                    "JaCoCo MBean 'org.jacoco:type=Runtime' not found. " +
                    "Ensure the JaCoCo agent is attached with output=none.");
                return ResponseEntity.status(503).body(body);
            }

            // getExecutionData(boolean reset) — false: read without clearing counters
            byte[] data = (byte[]) server.invoke(
                mbeanName,
                "getExecutionData",
                new Object[]{Boolean.FALSE},
                new String[]{"boolean"}
            );

            Path output = Paths.get(EXEC_PATH);
            Files.createDirectories(output.getParent());
            Files.write(output, data);

            body.put("status", "ok");
            body.put("execFile", EXEC_PATH);
            body.put("bytes", data.length);
            body.put("hint",
                "Run: mvn jacoco:report -f demo-app/pom.xml  →  demo-app/target/site/jacoco/index.html");
            return ResponseEntity.ok(body);

        } catch (IOException e) {
            body.put("status", "error");
            body.put("message", "Failed to write exec file: " + e.getMessage());
            return ResponseEntity.status(500).body(body);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("message", "Failed to read JaCoCo MBean: " + e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }
}
