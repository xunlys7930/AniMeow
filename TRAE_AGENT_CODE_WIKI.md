# Trae Agent 代码 Wiki

## 1. 项目概述

### 1.1 项目简介
Trae Agent 是一个基于大语言模型（LLM）的通用软件工程任务代理系统，提供强大的命令行界面，能够理解自然语言指令并执行复杂的软件开发工作流。该项目由 ByteDance 开发，采用 MIT 许可证开源。

- **GitHub 仓库**: https://github.com/bytedance/trae-agent
- **当前版本**: 0.1.0
- **主要特点**: 研究友好的架构，便于修改、扩展和分析

### 1.2 核心特性
- **Lakeview**: 提供简洁的代理执行步骤摘要
- **多 LLM 支持**: OpenAI、Anthropic、Doubao、Azure、OpenRouter、Ollama 和 Google Gemini
- **丰富的工具生态**: 文件编辑、Bash 执行、顺序思考等
- **交互模式**: 支持对话式的迭代开发
- **轨迹记录**: 详细记录所有代理操作，便于调试和分析
- **灵活配置**: 基于 YAML 的配置，支持环境变量
- **Docker 模式**: 支持在 Docker 容器中安全执行任务

---

## 2. 项目架构

### 2.1 目录结构
```
trae-agent/
├── trae_agent/              # 核心代码包
│   ├── agent/               # 代理模块
│   │   ├── __init__.py
│   │   ├── agent.py         # 代理工厂类
│   │   ├── agent_basics.py  # 代理基础功能
│   │   ├── base_agent.py    # 基础代理类
│   │   ├── docker_manager.py# Docker 管理
│   │   └── trae_agent.py    # Trae Agent 具体实现
│   ├── tools/               # 工具模块
│   │   ├── ckg/             # CKG 相关工具
│   │   ├── __init__.py
│   │   ├── base.py          # 工具基类
│   │   ├── bash_tool.py     # Bash 执行工具
│   │   ├── ckg_tool.py      # CKG 工具
│   │   ├── docker_tool_executor.py  # Docker 工具执行器
│   │   ├── edit_tool.py     # 编辑工具
│   │   ├── edit_tool_cli.py # 编辑工具 CLI
│   │   ├── json_edit_tool.py        # JSON 编辑工具
│   │   ├── json_edit_tool_cli.py    # JSON 编辑工具 CLI
│   │   ├── mcp_tool.py      # MCP (Model Context Protocol) 工具
│   │   ├── run.py           # 工具运行器
│   │   ├── sequential_thinking_tool.py  # 顺序思考工具
│   │   └── task_done_tool.py        # 任务完成工具
│   ├── utils/               # 工具函数模块
│   ├── prompt/              # 提示词模块
│   ├── dist/                # 编译后的工具
│   ├── __init__.py
│   └── cli.py               # 命令行入口
├── docs/                    # 文档目录
├── tests/                   # 测试目录
├── evaluation/              # 评估代码
├── pyproject.toml           # 项目配置
├── trae_config.yaml.example # 配置文件示例
└── README.md
```

### 2.2 核心模块依赖关系
```
cli.py (入口)
  ↓
Agent (工厂类)
  ↓
BaseAgent (基础代理)
  ↓
TraeAgent (具体实现)
  ↓
工具调用器 + 模型客户端
  ↓
各种工具 (BashTool, EditTool, 等)
  ↓
执行实际任务
```

---

## 3. 核心模块详解

### 3.1 代理模块 (`trae_agent/agent/`)

#### 3.1.1 `base_agent.py`
**用途**: 定义代理的基础抽象类和接口
**核心功能**:
- 管理消息历史和上下文
- 处理工具调用
- 执行代理循环
- 轨迹记录

#### 3.1.2 `trae_agent.py`
**用途**: Trae Agent 的具体实现
**特点**:
- 集成 Lakeview 功能
- 支持多工具并行调用
- 提供完整的任务解决流程

#### 3.1.3 `agent.py`
**用途**: 代理工厂类，负责创建特定类型的代理实例
**主要类**: `Agent`
**功能**:
- 根据配置创建对应的代理实例
- 处理代理初始化
- 管理执行流程

#### 3.1.4 `docker_manager.py`
**用途**: 管理 Docker 容器环境
**功能**:
- 创建和管理 Docker 容器
- 处理 Docker 镜像构建
- 管理容器生命周期
- 提供隔离的执行环境

### 3.2 工具模块 (`trae_agent/tools/`)

#### 3.2.1 工具基类 (`base.py`)
定义了所有工具的通用接口：
- `name`: 工具名称
- `description`: 工具描述
- `input_schema`: 输入参数模式
- `execute()`: 执行方法

#### 3.2.2 主要工具
| 工具名称 | 文件 | 功能描述 |
|---------|------|---------|
| `bash` | `bash_tool.py` | 执行 Bash 命令 |
| `str_replace_based_edit_tool` | `edit_tool.py` | 基于字符串替换的文件编辑 |
| `json_edit_tool` | `json_edit_tool.py` | JSON 文件编辑 |
| `sequentialthinking` | `sequential_thinking_tool.py` | 顺序思考和规划 |
| `task_done` | `task_done_tool.py` | 标记任务完成 |
| `mcp_tool` | `mcp_tool.py` | Model Context Protocol 工具集成 |

### 3.3 CLI 模块 (`cli.py`)
**入口点**: `trae-cli`
**主要命令**:
- `run`: 执行单次任务
- `interactive`: 启动交互模式
- `show-config`: 显示当前配置

**命令行参数**:
| 参数 | 说明 |
|-----|------|
| `--provider, -p` | LLM 提供商 |
| `--model, -m` | 使用的具体模型 |
| `--working-dir, -w` | 工作目录 |
| `--trajectory-file, -t` | 轨迹文件保存路径 |
| `--config-file` | 配置文件路径 |
| `--must-patch, -mp` | 是否强制生成补丁 |
| `--docker-image` | Docker 镜像 |
| `--docker-container-id` | 现有 Docker 容器 ID |
| `--dockerfile-path` | Dockerfile 路径 |

---

## 4. 配置系统

### 4.1 配置文件格式
推荐使用 YAML 格式配置 (`trae_config.yaml`)：

```yaml
agents:
  trae_agent:
    enable_lakeview: true
    model: trae_agent_model
    max_steps: 200
    tools:
      - bash
      - str_replace_based_edit_tool
      - sequentialthinking
      - task_done

model_providers:
  anthropic:
    api_key: your_anthropic_api_key
    provider: anthropic
  openai:
    api_key: your_openai_api_key
    provider: openai

models:
  trae_agent_model:
    model_provider: anthropic
    model: claude-4-sonnet
    max_tokens: 4096
    temperature: 0.5
    top_p: 1
    top_k: 0
    max_retries: 10
    parallel_tool_calls: true

lakeview:
  model: lakeview_model

mcp_servers:
  playwright:
    command: npx
    args:
      - "@playwright/mcp@0.0.27"
```

### 4.2 配置优先级
1. 命令行参数
2. 配置文件 (`trae_config.yaml`)
3. 环境变量
4. 默认值

### 4.3 环境变量
- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `GOOGLE_API_KEY`
- `OPENROUTER_API_KEY`
- `DOUBAO_API_KEY`
- `TRAE_CONFIG_FILE`

---

## 5. 工具系统

### 5.1 工具调用流程
1. 代理根据任务需求选择工具
2. LLM 生成工具调用参数
3. 工具执行器执行工具
4. 结果返回给代理
5. 代理根据结果继续或结束

### 5.2 扩展新工具
要创建新工具，需要：
1. 继承 `BaseTool` 类
2. 实现 `name`、`description`、`input_schema`
3. 实现 `execute()` 方法
4. 在工具注册点添加

---

## 6. 运行方式

### 6.1 安装
```bash
git clone https://github.com/bytedance/trae-agent.git
cd trae-agent
uv sync --all-extras
source .venv/bin/activate
```

### 6.2 基本使用
```bash
# 配置
cp trae_config.yaml.example trae_config.yaml
# 编辑 trae_config.yaml 填入 API 密钥

# 执行单次任务
trae-cli run "创建一个 hello world 脚本"

# 交互模式
trae-cli interactive
```

### 6.3 Docker 模式
```bash
# 使用 Docker 镜像
trae-cli run "创建项目" --docker-image python:3.11

# 挂载工作目录
trae-cli run "测试项目" --docker-image python:3.11 --working-dir ./project

# 附加到现有容器
trae-cli run "调试" --docker-container-id <container_id>

# 从 Dockerfile 构建
trae-cli run "构建" --dockerfile-path ./Dockerfile
```

### 6.4 交互模式命令
- 输入任意任务描述执行
- `status`: 显示代理状态
- `help`: 显示帮助
- `clear`: 清屏
- `exit`/`quit`: 退出会话

---

## 7. 依赖关系

### 7.1 核心依赖
| 依赖包 | 版本要求 | 用途 |
|--------|---------|------|
| `openai` | >=1.86.0 | OpenAI API 客户端 |
| `anthropic` | >=0.54.0, <=0.60.0 | Anthropic API 客户端 |
| `click` | >=8.0.0 | 命令行界面 |
| `google-genai` | >=1.24.0 | Google Gemini API |
| `jsonpath-ng` | >=1.7.0 | JSON 路径查询 |
| `pydantic` | >=2.0.0 | 数据验证 |
| `python-dotenv` | >=1.0.0 | 环境变量加载 |
| `rich` | >=13.0.0 | 丰富的终端输出 |
| `ollama` | >=0.5.1 | Ollama 本地模型 |
| `tree-sitter-languages` | ==1.10.2 | 语法分析 |
| `tree-sitter` | ==0.21.3 | 语法分析核心 |
| `ruff` | >=0.12.4 | 代码格式化和 lint |
| `mcp` | ==1.12.2 | Model Context Protocol |
| `asyncclick` | >=8.0.0 | 异步命令行 |
| `pyyaml` | >=6.0.2 | YAML 解析 |
| `textual` | >=0.50.0 | 终端 UI |

### 7.2 可选依赖
- **测试**: `pytest`, `pytest-asyncio`, `pytest-mock`, `pytest-cov`, `pre-commit`
- **评估**: `datasets`, `docker`, `pexpect`, `unidiff`

---

## 8. 技术细节

### 8.1 执行流程
1. 解析配置和命令行参数
2. 初始化代理和工具
3. 进入代理循环：
   - LLM 生成下一步操作
   - 执行工具调用
   - 更新状态和消息历史
   - 检查是否完成
4. 保存轨迹记录
5. 返回结果

### 8.2 轨迹记录
轨迹文件包含：
- 所有 LLM 交互
- 代理执行步骤
- 工具调用细节
- 执行元数据

---

## 9. 开发贡献

### 9.1 代码风格
- 使用 Ruff 进行代码格式化和 lint
- 最大行长度：100 字符
- 遵循 Python 最佳实践

### 9.2 测试
运行测试：
```bash
pytest tests/
```

---

## 10. 参考文献

- **技术报告**: https://arxiv.org/abs/2507.23370
- **GitHub 仓库**: https://github.com/bytedance/trae-agent
- **项目官网**: https://www.trae.ai/
