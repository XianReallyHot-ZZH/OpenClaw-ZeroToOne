# chat

## 添加子模块
```bash
git submodule add https://github.com/shareAI-lab/claw0 vendors/claw0
```

## claw0 项目分析

仔细阅读 @vendors/claw0 的代码，撰写一个详细的架构分析文档，如需图表，使用 mermaid chart。文档放在: ./specs/learn-claw0-arch.md


## claw-4j 项目分析

仔细阅读 @vendors/claw0 项目内容，然后请你分析该项目能不能用java语言及java相关技术栈（框架）进行重写，分析完后撰写一份重写分析文档，给出可行性分析，如果可以重写，给出重写建议与方案。如需图表，使用 mermaid chart，文档放在: @specs/目录下

根据 @specs/claw0-java-rewrite-analysis.md 文档，选定轻量级重写方案，撰写一份详细的实施计划，如需图表，使用 mermaid chart，文档放在 @specs/目录下

文件清单：
（1）@vendors/claw0 是重写对象claw0的源码目录
（2）@Specs/learn-claw0-arch.md 是claw0的架构分析文档
（3）@specs/claw0-java-rewrite-analysis.md 是claw0的重写分析文档
现在你的任务是根据  @specs/claw0-java-rewrite-analysis.md 文档，选定springboot企业级重写方案，撰写一份详细的实施计划，如需图表，使用 mermaid chart，文档放在 @specs/enterprise-claw0 目录下。有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。


# 检视项目

检视 @specs/light-claw0-java-rewrite-plan.md 文档，要求 java 版本为 21，其他依赖的版本都调整为最新的版本，然后论证方案的可行性，详细检视方案还有没有欠缺，还有没有没考虑的地方，还有没有可以优化的地方。有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。

详细检视 @specs/enterprise-claw0 目录下的文档，要求 java 版本为 21，其他依赖的版本都调整为最新的版本，然后论证方案的可行性，详细检视方案还有没有欠缺，还有没有没考虑的地方，还有没有可以优化的地方。有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。


# 编码执行计划

根据 @specs/light-claw0-java-rewrite-plan.md 撰写一份详细的、可实施的、可落地的、规划合理的编码执行计划，如需图表，使用 mermaid chart，文档放在 @specs/目录下。有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。

核心任务：
根据 @specs/enterprise-claw0 目录下的方案文档, 撰写一份详细的、可实施的、可落地的、规划合理的编码执行计划，如需图表，使用 mermaid chart，文档放在 @specs/enterprise-claw0 目录下。
其他注意点：
（1）有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。
（2）@vendors/claw0 是重写对象claw0的源码目录，所有代码都在该目录下。
（3）如果文档太大了，尝试分阶段独立写文档，每个阶段的文档放在 @specs/enterprise-claw0/04-execution-plan/ 目录下。


文件清单：
（1）@vendors/claw0 是重写对象claw0的源码目录
（2）@Specs/learn-claw0-arch.md 是claw0的架构分析文档
（3）@specs/claw0-java-rewrite-analysis.md 是claw0的重写分析文档
（4）@specs/enterprise-claw0/00-overview.md 是claw0的企业级重写计划总览文档
（5）@specs/enterprise-claw0/01-module-design.md 是claw0的企业级重写模块文档
（6）@specs/enterprise-claw0/02-api-and-dataflow.md 是claw0的企业级重写api和数据flow文档
（7）@specs/enterprise-claw0/03-testing-and-roadmap.md 是claw0的企业级重写测试和路线图文档
（8）@specs/enterprise-claw0/04-execution-plan/ 是claw0的企业级重写编码执行计划目录，包含所有编码执行计划文档。
详细检视 @specs/enterprise-claw0/04-execution-plan/ 目录下的整套执行计划文档，详细检视方案还有没有欠缺，还有没有没考虑的地方，还有没有可以优化的地方。有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。



# 项目执行
文件清单：
（1）@vendors/claw0 是重写对象claw0的源码目录
（2）@Specs/learn-claw0-arch.md 是claw0的架构分析文档
（3）@specs/claw0-java-rewrite-analysis.md 是claw0的重写分析文档
（4）@specs/light-claw0/light-claw0-java-rewrite-plan.md 是claw0的轻量级重写计划
（5）@specs/light-claw0/light-claw0-java-coding-execution-plan.md 是claw0的轻量级重写编码执行计划
现在你的任务是根据 @specs/light-claw0/light-claw0-java-coding-execution-plan.md 文档，开始编码实施，代码放在 @claw-4j/light-claw-4j 目录下,要求代码注释用中文，注释要详细。执行过程中，有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。




文件清单：
（1）@vendors/claw0 是重写对象claw0的源码目录
（2）@Specs/learn-claw0-arch.md 是claw0的架构分析文档
（3）@specs/claw0-java-rewrite-analysis.md 是claw0的重写分析文档
（4）@specs/enterprise-claw0/00-overview.md 是claw0的企业级重写计划总览文档
（5）@specs/enterprise-claw0/01-module-design.md 是claw0的企业级重写模块文档
（6）@specs/enterprise-claw0/02-api-and-dataflow.md 是claw0的企业级重写api和数据flow文档
（7）@specs/enterprise-claw0/03-testing-and-roadmap.md 是claw0的企业级重写测试和路线图文档
（8）@specs/enterprise-claw0/04-execution-plan/ 是claw0的企业级重写编码执行计划目录，包含所有编码执行计划文档。
现在你的任务是根据 @specs/enterprise-claw0/04-execution-plan/ 目录下的整套执行计划文档，开始编码实施，代码放在 @claw-4j/enterprise-claw-4j 目录下,要求代码注释用中文，注释要详细。执行过程中，有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。



## 项目执行结果检视
文件清单：
（1）@vendors/claw0 是重写对象claw0的源码目录
（2）@Specs/learn-claw0-arch.md 是claw0的架构分析文档
（3）@specs/claw0-java-rewrite-analysis.md 是claw0的重写分析文档
（4）@specs/enterprise-claw0/00-overview.md 是claw0的企业级重写计划总览文档
（5）@specs/enterprise-claw0/01-module-design.md 是claw0的企业级重写模块文档
（6）@specs/enterprise-claw0/02-api-and-dataflow.md 是claw0的企业级重写api和数据flow文档
（7）@specs/enterprise-claw0/03-testing-and-roadmap.md 是claw0的企业级重写测试和路线图文档
（8）@specs/enterprise-claw0/04-execution-plan/ 是claw0的企业级重写编码执行计划目录，包含所有编码执行计划文档。
（9）@claw-4j/enterprise-claw-4j/ 是claw0的企业级重写项目代码目录，所有代码都在该目录下。
现在你的任务是全方面深入检视 @claw-4j/enterprise-claw-4j 目录下的代码，检视项目是否符合企业级重写计划，深入检视代码的完成度、完整度、质量。执行过程中，有任何不明确的地方，通过向我提问的方式来获取、确认更多信息。

最后再全面检视一遍 @claw-4j/enterprise-claw-4j 目录下的代码，确保功能完整，逻辑正确，能够正常运行且符合预期。