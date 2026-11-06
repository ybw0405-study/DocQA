# 分层规则与 ArchUnit 门禁

## 1. 分层定义

所有 Java 模块内部只允许以下单向调用：

```text
Controller -> Service -> Manager -> Repository
                  \------> Repository（仅允许简单只读查询）
```

| 层 | 可以做什么 | 不可以做什么 |
|---|---|---|
| `controller` | 参数校验、鉴权注解、调用 Service、把领域结果转换为响应 DTO | 写业务分支、直接访问 Repository、拼 SQL、调用外部 HTTP |
| `service` | 业务编排、事务边界、领域规则、发布事件 | 直接接收 HTTP 请求、返回 JPA 实体、在事务中调用 LLM/外部 HTTP |
| `manager` | 对多个 Repository 或外部基础设施做组合封装 | 承载 HTTP 协议细节、反向调用 Controller |
| `repository` | 数据访问、查询条件封装 | 依赖 Service/Controller、发消息、调用外部服务 |

补充约定：

- Controller 只能依赖 Service 与 DTO，不得出现 `@Transactional`。
- `@Transactional` 统一放在 Service 的公共方法上；事务内不得调用 LLM、Python 或任何外部 HTTP。
- 对外响应必须使用 DTO，不得直接序列化 JPA 实体。
- 包名使用 `..controller..`、`..service..`、`..manager..`、`..repository..` 的规范路径，ArchUnit 依赖此约定。

## 2. ArchUnit 执行方式

依赖与测试基座：

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version>
  <scope>test</scope>
</dependency>
```

规则放在各业务模块的 `src/test/java/com/docqa/architecture/ArchitectureTest.java`，或放入一个仅以 `test` 作用域依赖所有业务模块的架构测试模块。每个模块的 CI 测试都会执行同一个类名的规则，任何一条失败即阻断合并。

完整示例（语法可直接使用，实际落地时若采用统一架构测试模块，需保证被测模块类在测试 classpath 中）：

```java
package com.docqa.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "com.docqa",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {

    /**
     * 规则一：Controller 不得直接依赖 Repository。
     * 这是最常见的“为了少写一层，直接在 Controller 查库”的退化物。
     */
    @ArchTest
    static final ArchRule controllers_must_not_access_repositories =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("Controller 只能编排 Service，数据访问必须经过业务层");

    /**
     * 规则二：四层调用方向必须单向。
     */
    @ArchTest
    static final ArchRule layering_is_respected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Controller").definedBy("..controller..")
                    .layer("Service").definedBy("..service..")
                    .layer("Manager").definedBy("..manager..")
                    .layer("Repository").definedBy("..repository..")
                    .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                    .whereLayer("Manager").mayOnlyBeAccessedByLayers("Service")
                    .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Manager")
                    .because("Controller -> Service -> Manager/Repository 只能单向依赖");

    /**
     * 规则三：只有 agent-gateway 可以引入 WebClient。
     * 对应“唯一同步跨语言调用边界”。
     */
    @ArchTest
    static final ArchRule only_agent_gateway_may_use_webclient =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
                    .because("除 agent-gateway 外，任何 Java 模块不得同步调用 Python");

    /**
     * 规则四：只有 agent-gateway 可以引入 RestTemplate。
     */
    @ArchTest
    static final ArchRule only_agent_gateway_may_use_resttemplate =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                    .because("RestTemplate 同样是绕过 Python 治理边界的后门");

    /**
     * 规则五：RestClient 与 WebClient/RestTemplate 同等对待，防止换一个客户端绕过规则。
     */
    @ArchTest
    static final ArchRule only_agent_gateway_may_use_restclient =
            noClasses()
                    .that().resideOutsideOfPackage("..agentgateway..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestClient")
                    .because("更换 HTTP 客户端不能改变跨语言调用边界");

    /**
     * 规则六：Repository 不得反向依赖业务层。
     */
    @ArchTest
    static final ArchRule repositories_must_not_depend_on_services_or_controllers =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..controller..", "..service..")
                    .because("数据访问层不得感知业务编排层");
}
```

## 3. 规则与风险的对应关系

| 规则 | 拦截的典型代码 | 可能引发的生产问题 |
|---|---|---|
| Controller 不依赖 Repository | Controller 注入 `DocumentRepository` 直接查状态 | 绕过权限与业务校验；事务边界错误；后续状态机改动漏改入口 |
| 四层单向 | Repository 反向调用 Service、Manager 调 Controller | 循环依赖、启动失败、难以测试、领域逻辑散落 |
| 只有 agent-gateway 用 WebClient/RestTemplate/RestClient | 在 `document-service` 里直接调 Python | 绕过 token 预算、熔断、PII 脱敏、trace 传播与审计 |
| Repository 不依赖 Service/Controller | Repository 内触发业务事件 | 数据访问副作用不可追踪，事务回滚后消息仍可能发出 |

## 4. 与人工 Code Review 的关系

ArchUnit 不是替代 Code Review，而是把**确定性的架构事实**从人工检查中剔除：

- 人工 Review 会疲劳，几十个 MR 后可能漏看一个 `import`；ArchUnit 每个 PR 都执行，不受情绪和赶进度影响。
- ArchUnit 分析字节码依赖，不只检查源码里的显式字符串；即使通过工具类间接引用，也可能被识别。
- 规则失败是构建失败，不依赖 Reviewer 是否记得这条规范。

ArchUnit 无法覆盖的部分仍靠 Review：运行时通过反射/配置加载的客户端、MQ 事件的语义一致性、事务位置是否合理、是否泄露实体、跨服务数据复制是否越权。这些由 `docs/03-review/review-checklist.md` 的检查项和集成测试补充。任何 `@ArchIgnore` 都必须附带 ADR 编号与到期时间，不能作为永久豁免。

## 5. CI 接入

CI 的独立步骤执行：

```bash
mvn -B -ntp -Dtest='*ArchitectureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

任一条规则失败，PR check 变红，配合分支保护禁止合并。若未来新增客户端（例如 `java.net.http.HttpClient`），必须同步扩展规则，并在规则文件中说明新增原因。

---

拦截的是什么问题：拦截人工评审容易漏掉的分层越界和"绕过 agent-gateway 私自调用 Python"的架构后门，让违规依赖在合并前直接变成构建失败。
