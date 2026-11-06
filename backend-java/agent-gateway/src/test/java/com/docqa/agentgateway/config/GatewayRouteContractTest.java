package com.docqa.agentgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * 网关路由前缀与契约 paths 必须双向对账（S9-6，关闭 S4-M2）。
 *
 * <p>路由与契约之间没有编译期约束：契约新增路径而网关忘配前缀时，Spring 不会报错，
 * 线上表现为 404。这里直接解析真实 {@code application.yml} 的 {@code Path} 谓词、
 * 契约的 {@code paths} 与网关本地控制器的映射，任何一方单方面新增或删除都会让测试变红。
 *
 * <p>本地前缀白名单只用于「网关自己处理、不转发给下游」的路径；白名单本身也受控制器映射约束，
 * 不能靠手写放行来抹平对账失败。
 */
class GatewayRouteContractTest {

    /** 网关本地控制器处理的路径前缀；必须与 {@code controller} 包的类级 @RequestMapping 逐一对应。 */
    private static final Set<String> LOCAL_PREFIXES = Set.of("/api/v1/admin", "/api/v1/conversations");

    private static final String LOCAL_CONTROLLER_PACKAGE = "com.docqa.agentgateway.controller";
    private static final String PATH_PREDICATE = "Path=";
    private static final String CONTRACT = "docs/02-design/openapi-java.yaml";

    private record Route(String id, List<String> pathPatterns) {}

    @Test
    void everyContractPathHasExactlyOneOwner() throws IOException {
        List<Route> routes = routes();
        for (String path : contractPaths()) {
            assertThat(ownersOf(path, routes))
                    .as("契约路径 %s 必须恰好有一个归属：无归属在线上是 404，多归属会让转发与本地控制器争抢同一请求。"
                            + "新增契约路径时，要么在 application.yml 补 Path 前缀，要么登记本地白名单并实现控制器。", path)
                    .hasSize(1);
        }
    }

    @Test
    void anUnroutedContractPathHasNoOwner() throws IOException {
        assertThat(ownersOf("/api/v1/schedule/events", routes()))
                .as("守卫：契约新增路径而网关漏配前缀时归属为空，everyContractPathHasExactlyOneOwner 因此变红")
                .isEmpty();
    }

    @Test
    void everyRoutePathPatternStillOwnsAContractPath() throws IOException {
        Set<String> contractPaths = contractPaths();
        for (Route route : routes()) {
            for (String pattern : route.pathPatterns()) {
                assertThat(contractPaths.stream().anyMatch(path -> covers(pattern, path)))
                        .as("路由 %s 的 %s 在契约里已没有对应路径：僵死前缀会掩盖真实的路径迁移或改名", route.id(), pattern)
                        .isTrue();
            }
        }
    }

    @Test
    void everyLocalPrefixStillOwnsAContractPath() throws IOException {
        Set<String> contractPaths = contractPaths();
        for (String prefix : LOCAL_PREFIXES) {
            assertThat(contractPaths.stream().anyMatch(path -> covers(prefix, path)))
                    .as("本地白名单 %s 在契约里已没有对应路径：删改契约路径时也必须同步清理白名单", prefix)
                    .isTrue();
        }
    }

    @Test
    void routePrefixesAndLocalPrefixesDoNotOverlap() throws IOException {
        for (Route route : routes()) {
            for (String pattern : route.pathPatterns()) {
                for (String prefix : LOCAL_PREFIXES) {
                    assertThat(overlaps(stripWildcard(pattern), prefix))
                            .as("路由 %s 的 %s 与本地白名单 %s 重叠：请求会同时命中两条归属规则", route.id(), pattern, prefix)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void localWhitelistIsBackedByGatewayControllers() {
        Set<String> controllerPrefixes = controllerPrefixes();
        for (String prefix : LOCAL_PREFIXES) {
            assertThat(controllerPrefixes.stream().anyMatch(controller -> covers(prefix, controller)))
                    .as("本地白名单 %s 下没有任何控制器前缀：白名单只能豁免自己真正处理的路径，不能手写放行", prefix)
                    .isTrue();
        }
        for (String controller : controllerPrefixes) {
            long covering = LOCAL_PREFIXES.stream().filter(prefix -> covers(prefix, controller)).count();
            assertThat(covering)
                    .as("控制器前缀 %s 必须恰好被一个白名单项覆盖，重复覆盖会让归属出现歧义", controller)
                    .isEqualTo(1);
        }
    }

    @Test
    void locallyServedContractPathsAreBackedByControllerMappings() throws IOException {
        Set<String> locallyServed = new TreeSet<>();
        for (String path : contractPaths()) {
            if (LOCAL_PREFIXES.stream().anyMatch(prefix -> covers(prefix, path))) {
                locallyServed.add(path);
            }
        }

        assertThat(controllerMappings())
                .as("契约里由网关本地处理的路径必须真的在控制器上有映射，反之亦然；否则契约写 200、线上却是 404")
                .containsExactlyInAnyOrderElementsOf(locallyServed);
    }

    private static List<String> ownersOf(String path, List<Route> routes) {
        List<String> owners = new ArrayList<>();
        for (Route route : routes) {
            if (route.pathPatterns().stream().anyMatch(pattern -> covers(pattern, path))) {
                owners.add("路由 " + route.id());
            }
        }
        if (LOCAL_PREFIXES.stream().anyMatch(prefix -> covers(prefix, path))) {
            owners.add("本地白名单");
        }
        return List.copyOf(owners);
    }

    private static List<Route> routes() throws IOException {
        Map<String, Object> root = loadYaml(new ClassPathResource("application.yml"));
        List<Route> routes = new ArrayList<>();
        for (Object rawRoute : asList(navigate(root, "spring", "cloud", "gateway", "mvc", "routes"))) {
            Map<String, Object> route = asMap(rawRoute);
            String id = String.valueOf(route.get("id"));
            List<String> patterns = new ArrayList<>();
            for (Object rawPredicate : asList(route.get("predicates"))) {
                String predicate = String.valueOf(rawPredicate);
                assertThat(predicate)
                        .as("路由 %s 的谓词 %s 不是 Path：无法参与前缀对账，新增其它谓词时必须同步扩展本测试", id, predicate)
                        .startsWith(PATH_PREDICATE);
                for (String pattern : predicate.substring(PATH_PREDICATE.length()).split(",")) {
                    String stripped = pattern.strip();
                    assertThat(stripped).as("路由 %s 的 Path 谓词必须是绝对路径", id).startsWith("/");
                    patterns.add(stripped);
                }
            }
            assertThat(patterns).as("路由 %s 缺少 Path 谓词", id).isNotEmpty();
            routes.add(new Route(id, List.copyOf(patterns)));
        }

        assertThat(routes).as("application.yml 缺少 spring.cloud.gateway.mvc.routes").isNotEmpty();
        assertThat(routes).as("路由 id 不能重复").extracting(Route::id).doesNotHaveDuplicates();
        return routes;
    }

    private static Set<String> contractPaths() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(CONTRACT);
            if (Files.isRegularFile(candidate)) {
                try (Reader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                    Map<String, Object> contract = asMap(new Yaml().load(reader));
                    Set<String> paths = new TreeSet<>(asMap(contract.get("paths")).keySet());
                    assertThat(paths).as("契约缺少 paths").isNotEmpty();
                    return paths;
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(CONTRACT + " not found from " + Path.of("").toAbsolutePath());
    }

    private static Set<String> controllerPrefixes() {
        Set<String> prefixes = new TreeSet<>();
        for (Class<?> type : gatewayControllers()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            assertThat(mapping).as("%s 缺少类级 @RequestMapping", type.getName()).isNotNull();
            prefixes.addAll(Arrays.asList(mapping.path()));
        }
        return prefixes;
    }

    private static Set<String> controllerMappings() {
        Set<String> mappings = new TreeSet<>();
        for (Class<?> type : gatewayControllers()) {
            String[] prefixes = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class).path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        mappings.add(join(prefix, path));
                    }
                }
            }
        }
        return mappings;
    }

    private static List<Class<?>> gatewayControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(LOCAL_CONTROLLER_PACKAGE)) {
            try {
                controllers.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("无法加载控制器 " + definition.getBeanClassName(), exception);
            }
        }
        assertThat(controllers).as("没有扫描到网关本地控制器").isNotEmpty();
        return controllers;
    }

    private static String join(String prefix, String path) {
        if (path.isEmpty()) {
            return prefix;
        }
        String head = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return head + (path.startsWith("/") ? path : "/" + path);
    }

    /** 将 Path 谓词（或本地前缀）还原成不带通配符的基前缀，再按路径段判断覆盖。 */
    private static boolean covers(String prefix, String path) {
        String base = stripWildcard(prefix);
        return path.equals(base) || path.startsWith(base + "/");
    }

    private static String stripWildcard(String pattern) {
        int wildcard = pattern.indexOf('*');
        String base = wildcard < 0 ? pattern : pattern.substring(0, wildcard);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static boolean overlaps(String left, String right) {
        return left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    private static Map<String, Object> loadYaml(ClassPathResource resource) throws IOException {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return asMap(new Yaml().load(reader));
        }
    }

    private static Object navigate(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = asMap(current).get(key);
        }
        return current;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
