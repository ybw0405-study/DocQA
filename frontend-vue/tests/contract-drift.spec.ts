/**
 * 契约 ↔ 前端类型的常驻漂移测试（8B-2e）。
 *
 * 唯一事实源是 `docs/02-design/openapi-java.yaml`：本文件不复制契约内容当「期望值」，
 * 而是当场从契约文本解析出 schema 名单、字段集合、required、枚举全集与数值约束，
 * 再与 `src/types/*.ts` 的声明比对。契约一改，这里必然变红，从而强制一次裁决。
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

const ROOT = process.cwd();
const CONTRACT_PATH = join(ROOT, '..', 'docs', '02-design', 'openapi-java.yaml');
const contractLines = readFileSync(CONTRACT_PATH, 'utf8').split(/\r?\n/);

interface Block {
  name: string;
  body: string[];
}

/** 取 `  <section>:` 到下一个二级键之间的行。 */
function sectionLines(startKey: string): string[] {
  const start = contractLines.findIndex((line) => line === `  ${startKey}:`);
  if (start < 0) {
    throw new Error(`契约缺少区段 ${startKey}`);
  }
  const rest = contractLines.slice(start + 1);
  const stop = rest.findIndex((line) => /^ {2}[a-zA-Z][a-zA-Z0-9_]*:$/.test(line));
  return stop < 0 ? rest : rest.slice(0, stop);
}

/** 区段内每个 4 空格键是一个独立块。 */
function blocks(lines: string[]): Block[] {
  const out: Block[] = [];
  let current: Block | null = null;
  for (const line of lines) {
    const head = /^ {4}([A-Za-z][A-Za-z0-9_]*):$/.exec(line);
    if (head !== null) {
      current = { name: head[1], body: [] };
      out.push(current);
      continue;
    }
    if (current !== null) {
      current.body.push(line);
    }
  }
  return out;
}

const SCHEMA_BLOCKS = blocks(sectionLines('schemas'));
const RESPONSE_BLOCKS = blocks(sectionLines('responses'));
const PARAMETER_BLOCKS = blocks(sectionLines('parameters'));
const SCHEMAS = new Map(SCHEMA_BLOCKS.map((block) => [block.name, block.body]));
const SCHEMA_NAMES = SCHEMA_BLOCKS.map((block) => block.name);
const PATHS_LINES = (() => {
  // `paths:` 是一级键，上面的 sectionLines 只认二级键，因此单独取到 `components:` 之前。
  const start = contractLines.findIndex((line) => line === 'paths:');
  if (start < 0) {
    throw new Error('契约缺少区段 paths');
  }
  const rest = contractLines.slice(start + 1);
  const stop = rest.findIndex((line) => line === 'components:');
  return stop < 0 ? rest : rest.slice(0, stop);
})();

/**
 * 某个操作在契约里的正文：从它的 `operationId:` 行到下一个操作或下一个路径键为止。
 * 「这个操作用了哪些参数」只能按操作体判定，`components.parameters` 里存在某个参数
 * 不等于每个接口都引用了它。
 */
function operationBody(operationId: string): string[] {
  const start = PATHS_LINES.findIndex((line) => line === `      operationId: ${operationId}`);
  if (start < 0) {
    throw new Error(`契约缺少操作 ${operationId}`);
  }
  const rest = PATHS_LINES.slice(start + 1);
  const stops = [/^ {6}operationId: /, /^ {2}\S/]
    .map((pattern) => rest.findIndex((line) => pattern.test(line)))
    .filter((index) => index >= 0);
  return stops.length === 0 ? rest : rest.slice(0, Math.min(...stops));
}

/** 操作体里引用的 `components.parameters` 名字（响应引用是另一个前缀，不会被误收）。 */
function operationParameterRefs(body: string[]): string[] {
  return [...body.join('\n').matchAll(/#\/components\/parameters\/([A-Za-z0-9_]+)/g)].map((match) => match[1] ?? '');
}

/** 恰好缩进 8 空格、位于顶层 `properties:` 之后的键，即该 schema 的字段名。 */
function contractFields(body: string[]): string[] {
  const start = body.findIndex((line) => line === '      properties:');
  if (start < 0) {
    return [];
  }
  const names: string[] = [];
  for (let i = start + 1; i < body.length; i += 1) {
    const line = body[i];
    if (/^ {6}\S/.test(line) || /^ {4}\S/.test(line)) {
      break;
    }
    const match = /^ {8}([a-zA-Z][A-Za-z0-9_]*):/.exec(line);
    if (match !== null) {
      names.push(match[1]);
    }
  }
  return names;
}

function contractRequired(body: string[]): string[] {
  return contractList(body, '      required:');
}

function contractEnum(body: string[]): string[] {
  return contractList(body, '      enum:').map((value) => value.replace(/^['"]|['"]$/g, ''));
}

/** 收集某个 6 空格列表键下的 8 空格条目。 */
function contractList(body: string[], keyLine: string): string[] {
  const start = body.findIndex((line) => line === keyLine);
  if (start < 0) {
    return [];
  }
  const items: string[] = [];
  for (let i = start + 1; i < body.length; i += 1) {
    const match = /^ {8}- (.+)$/.exec(body[i]);
    if (match === null) {
      break;
    }
    items.push(match[1]);
  }
  return items;
}

/**
 * 字段子树里 10 空格 `enum:` 下的 12 空格条目。契约把管理面的四个枚举写在字段内联位置
 * 而不是独立 schema，上面的 `contractEnum` 只认 schema 顶层，因此需要按字段下钻一层。
 */
function contractFieldEnum(schema: string, field: string): string[] {
  const body = SCHEMAS.get(schema);
  if (body === undefined) {
    throw new Error(`契约缺少 schema ${schema}`);
  }
  const start = body.findIndex((line) => line === `        ${field}:`);
  if (start < 0) {
    throw new Error(`契约 ${schema} 缺少字段 ${field}`);
  }
  let enumStart = -1;
  for (let i = start + 1; i < body.length; i += 1) {
    if (/^ {0,8}\S/.test(body[i])) {
      break;
    }
    if (body[i] === '          enum:') {
      enumStart = i;
      break;
    }
  }
  if (enumStart < 0) {
    throw new Error(`契约 ${schema}.${field} 没有内联 enum`);
  }
  const items: string[] = [];
  for (let i = enumStart + 1; i < body.length; i += 1) {
    const match = /^ {12}- (.+)$/.exec(body[i]);
    if (match === null) {
      break;
    }
    items.push(match[1].replace(/^['"]|['"]$/g, ''));
  }
  return items;
}

/**
 * 事件帧 schema 的 `data` 子树（8 空格 `data:` 之下，直到下一个 ≤6 空格键）。
 * 帧的顶层只有 `event` 与 `data` 两个字段，真正的载荷字段缩在 `data` 里，
 * 因此上面按 8 空格解析顶层字段的函数用不上，需要单独下钻一层。
 */
function frameDataBody(schema: string): string[] {
  const body = SCHEMAS.get(schema);
  if (body === undefined) {
    throw new Error(`契约缺少 schema ${schema}`);
  }
  const start = body.findIndex((line) => line === '        data:');
  if (start < 0) {
    throw new Error(`契约 ${schema} 缺少 data 字段`);
  }
  const rest = body.slice(start + 1);
  const stop = rest.findIndex((line) => /^ {0,6}\S/.test(line));
  return stop < 0 ? rest : rest.slice(0, stop);
}

/** `data` 子树里 10 空格 `properties:` 下的 12 空格字段名。 */
function frameDataFields(dataBody: string[]): string[] {
  const start = dataBody.findIndex((line) => line === '          properties:');
  if (start < 0) {
    return [];
  }
  const names: string[] = [];
  for (let i = start + 1; i < dataBody.length; i += 1) {
    if (/^ {0,10}\S/.test(dataBody[i])) {
      break;
    }
    const match = /^ {12}([a-zA-Z][A-Za-z0-9_]*):/.exec(dataBody[i]);
    if (match !== null) {
      names.push(match[1]);
    }
  }
  return names;
}

/** `data` 子树里 10 空格 `required:` 下的 12 空格条目。 */
function frameDataRequired(dataBody: string[]): string[] {
  const start = dataBody.findIndex((line) => line === '          required:');
  if (start < 0) {
    return [];
  }
  const items: string[] = [];
  for (let i = start + 1; i < dataBody.length; i += 1) {
    const match = /^ {12}- (.+)$/.exec(dataBody[i]);
    if (match === null) {
      break;
    }
    items.push(match[1]);
  }
  return items;
}

/** 事件帧 `event` 字段子树里的 enum 取值（10 空格 `enum:` 下的 12 空格条目）。 */
function frameEventEnum(schema: string): string[] {
  const body = SCHEMAS.get(schema);
  if (body === undefined) {
    throw new Error(`契约缺少 schema ${schema}`);
  }
  const start = body.findIndex((line) => line === '        event:');
  if (start < 0) {
    throw new Error(`契约 ${schema} 缺少 event 字段`);
  }
  const rest = body.slice(start + 1);
  const enumStart = rest.findIndex((line) => line === '          enum:');
  if (enumStart < 0) {
    return [];
  }
  const items: string[] = [];
  for (let i = enumStart + 1; i < rest.length; i += 1) {
    const match = /^ {12}- (.+)$/.exec(rest[i]);
    if (match === null) {
      break;
    }
    items.push(match[1]);
  }
  return items;
}

/** `discriminator.mapping` 下的 10 空格键，即该请求实际接受类型的全集。 */
function contractDiscriminator(body: string[]): string[] {
  const start = body.findIndex((line) => line === '        mapping:');
  if (start < 0) {
    throw new Error('契约缺少 discriminator.mapping');
  }
  const keys: string[] = [];
  for (let i = start + 1; i < body.length; i += 1) {
    const match = /^ {10}([A-Za-z_]+):/.exec(body[i]);
    if (match === null) {
      break;
    }
    keys.push(match[1]);
  }
  return keys;
}

/** 某字段子树里的标量约束，如 `maxLength: 200`。 */
function contractConstraint(schema: string, field: string, keyword: string): string {
  const body = SCHEMAS.get(schema);
  if (body === undefined) {
    throw new Error(`契约缺少 schema ${schema}`);
  }
  const start = body.findIndex((line) => line === `        ${field}:`);
  if (start < 0) {
    throw new Error(`契约 ${schema} 缺少字段 ${field}`);
  }
  for (let i = start + 1; i < body.length; i += 1) {
    const line = body[i];
    if (/^ {6}\S/.test(line) || /^ {8}\S/.test(line) || /^ {4}\S/.test(line)) {
      break;
    }
    const match = new RegExp(`^ {10}${keyword}: (.+)$`).exec(line);
    if (match !== null) {
      return match[1].replace(/^['"]|['"]$/g, '');
    }
  }
  throw new Error(`契约 ${schema}.${field} 缺少 ${keyword}`);
}

/**
 * 数组项级约束：契约写在 `items:` 下、缩进 12 空格，
 * 上面的字段级解析器只认 10 空格，因此 `tags` 的 `items.maxLength` 必须单独取。
 */
function contractItemConstraint(schema: string, field: string, keyword: string): string {
  const body = SCHEMAS.get(schema);
  if (body === undefined) {
    throw new Error(`契约缺少 schema ${schema}`);
  }
  const fieldStart = body.findIndex((line) => line === `        ${field}:`);
  if (fieldStart < 0) {
    throw new Error(`契约 ${schema} 缺少字段 ${field}`);
  }
  const itemsStart = body.findIndex((line) => line === '          items:', fieldStart);
  if (itemsStart < 0) {
    throw new Error(`契约 ${schema}.${field} 没有 items 子树`);
  }
  for (let i = itemsStart + 1; i < body.length; i += 1) {
    const line = body[i];
    if (/^ {10}\S/.test(line) || /^ {8}\S/.test(line)) {
      break;
    }
    const match = new RegExp(`^ {12}${keyword}: (.+)$`).exec(line);
    if (match !== null) {
      return match[1].replace(/^['"]|['"]$/g, '');
    }
  }
  throw new Error(`契约 ${schema}.${field} 的 items 缺少 ${keyword}`);
}

/** `components.parameters` 里某个参数的 schema 约束，如 `DocumentSearchQuery.maxLength`。 */
function parameterConstraint(name: string, keyword: string): string {
  const block = PARAMETER_BLOCKS.find((entry) => entry.name === name);
  if (block === undefined) {
    throw new Error(`契约缺少参数 ${name}`);
  }
  const match = new RegExp(`^ {8}${keyword}: (.+)$`, 'm').exec(block.body.join('\n'));
  if (match === null) {
    throw new Error(`契约参数 ${name} 缺少 ${keyword}`);
  }
  return match[1].trim().replace(/^['"]|['"]$/g, '');
}

function tsText(file: string): string {
  return readFileSync(join(ROOT, file), 'utf8');
}

/** 递归收集匹配 `pattern` 的文件路径，跳过构建产物与依赖目录。 */
function sourceFilesUnder(root: string, pattern: RegExp): string[] {
  if (!existsSync(root)) {
    throw new Error(`对账需要读取 ${root}，该目录不存在`);
  }
  const found: string[] = [];
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const absolute = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'target' || entry.name === 'node_modules' || entry.name === '.git') {
          continue;
        }
        walk(absolute);
      } else if (pattern.test(absolute)) {
        found.push(absolute);
      }
    }
  };
  walk(root);
  return found;
}

/** `export interface X { ... }` 的成员名与可选性（泛型与 `extends 单名` 一并按声明行匹配）。 */
function tsMembers(file: string, iface: string): { required: string[]; optional: string[] } {
  const text = tsText(file);
  const declaration = new RegExp(
    `export interface ${iface}(?:<[^>]*>)?(?: extends [A-Za-z][A-Za-z0-9_]*)? \\{`,
 ).exec(text);
  if (declaration === null) {
    throw new Error(`${file} 缺少 interface ${iface}`);
  }
  const start = declaration.index;
  const body = text.slice(start, text.indexOf('\n}', start));
  const required: string[] = [];
  const optional: string[] = [];
  for (const line of body.split(/\r?\n/).slice(1)) {
    const match = /^ {2}([a-zA-Z][A-Za-z0-9_]*)(\?)?:/.exec(line);
    if (match !== null) {
      (match[2] === undefined ? required : optional).push(match[1]);
    }
  }
  return { required, optional };
}

/** `export type X = 'A' | 'B'` 或 `export const X: readonly Y[] = ['A', 'B']` 的字面量全集。 */
function tsLiterals(file: string, name: string, kind: 'type' | 'const'): string[] {
  const text = tsText(file);
  const anchor = kind === 'type' ? `export type ${name} =` : `export const ${name}`;
  const start = text.indexOf(anchor);
  if (start < 0) {
    throw new Error(`${file} 缺少声明：${anchor}`);
  }
  const end = text.indexOf(';', start);
  const chunk = text.slice(start, end < 0 ? start + 2000 : end);
  return [...chunk.matchAll(/'([^']+)'/g)].map((match) => match[1] ?? '');
}

function tsNumberConst(file: string, name: string): number {
  const match = new RegExp(`const ${name} = ([^;\\r\\n]+);`).exec(tsText(file));
  if (match === null) {
    throw new Error(`${file} 缺少常量 ${name}`);
  }
  return Number(match[1].trim().replace(/_/g, ''));
}

/** `const NAME[: T] = 'value'`：store 里模块私有的字符串默认参数，带类型标注也不影响取右侧。 */
function tsStringConst(file: string, name: string): string {
  const match = new RegExp(`const ${name}(?::[^=\\r\\n]+)? = '([^']*)'`).exec(tsText(file));
  if (match === null) {
    throw new Error(`${file} 缺少字符串常量 ${name}`);
  }
  return match[1];
}

function tsRegExp(file: string, name: string): RegExp {
  // 正则字面量内部可能出现 `;`（RRULE 的分隔符），因此按整行取，再按最后一个 `/` 切分模式体与 flags。
  const line = new RegExp(`^export const ${name} = (/.*)$`, 'm').exec(tsText(file));
  if (line === null) {
    throw new Error(`${file} 缺少正则常量 ${name}`);
  }
  const literal = line[1].replace(/;$/, '').trim();
  const split = literal.lastIndexOf('/');
  if (split < 1) {
    throw new Error(`${file} 的正则常量 ${name} 无法解析`);
  }
  return new RegExp(literal.slice(1, split), literal.slice(split + 1));
}

/** 只取成员名，供「字段集合是否一一对齐」使用。 */
function tsFieldNames(file: string, iface: string): string[] {
  const members = tsMembers(file, iface);
  return [...members.required, ...members.optional];
}

const TYPES = {
  api: 'src/types/api.ts',
  documents: 'src/types/documents.ts',
  conversations: 'src/types/conversations.ts',
  reminders: 'src/types/reminders.ts',
  notifications: 'src/types/notifications.ts',
  admin: 'src/types/admin.ts',
  modelConnections: 'src/types/modelConnections.ts',
  memories: 'src/types/memories.ts',
  users: 'src/types/users.ts',
} as const;

/** 前端声明镜像的对象 schema：契约名 ↔ TS 接口名。 */
const MIRRORED_OBJECTS: { schema: string; file: string; iface: string }[] = [
  { schema: 'PendingApproval', file: TYPES.conversations, iface: 'PendingApproval' },
  { schema: 'ApiEnvelope', file: TYPES.api, iface: 'ApiEnvelope' },
  { schema: 'FieldError', file: TYPES.api, iface: 'FieldError' },
  { schema: 'ProblemDetail', file: TYPES.api, iface: 'ProblemDetail' },
  { schema: 'LoginRequest', file: TYPES.api, iface: 'LoginRequest' },
  { schema: 'RegisterRequest', file: TYPES.api, iface: 'RegisterRequest' },
  { schema: 'LoginData', file: TYPES.api, iface: 'LoginData' },
  { schema: 'CurrentUserData', file: TYPES.api, iface: 'CurrentUserData' },
  { schema: 'UploadInitRequest', file: TYPES.documents, iface: 'UploadInitRequest' },
  { schema: 'PresignedPart', file: TYPES.documents, iface: 'PresignedPart' },
  { schema: 'UploadInitData', file: TYPES.documents, iface: 'UploadInitData' },
  { schema: 'CompletedPart', file: TYPES.documents, iface: 'CompletedPart' },
  { schema: 'CompleteUploadData', file: TYPES.documents, iface: 'CompleteUploadData' },
  { schema: 'DocumentStatusData', file: TYPES.documents, iface: 'DocumentStatusData' },
  { schema: 'DocumentListItemData', file: TYPES.documents, iface: 'DocumentListItemData' },
  { schema: 'DocumentListData', file: TYPES.documents, iface: 'DocumentListData' },
  { schema: 'DocumentRenameRequest', file: TYPES.documents, iface: 'DocumentRenameRequest' },
  { schema: 'DocumentTagsUpdateRequest', file: TYPES.documents, iface: 'DocumentTagsUpdateRequest' },
  { schema: 'AskRequest', file: TYPES.conversations, iface: 'AskRequest' },
  { schema: 'Citation', file: TYPES.conversations, iface: 'Citation' },
  { schema: 'AskData', file: TYPES.conversations, iface: 'AskData' },
  { schema: 'CreateConversationRequest', file: TYPES.conversations, iface: 'CreateConversationRequest' },
  { schema: 'ConversationData', file: TYPES.conversations, iface: 'ConversationData' },
  { schema: 'ConversationListItemData', file: TYPES.conversations, iface: 'ConversationListItemData' },
  { schema: 'ConversationListData', file: TYPES.conversations, iface: 'ConversationListData' },
  { schema: 'MessageHistoryItemData', file: TYPES.conversations, iface: 'MessageHistoryItemData' },
  { schema: 'MessageHistoryData', file: TYPES.conversations, iface: 'MessageHistoryData' },
  { schema: 'DeadlineReminderCreateRequest', file: TYPES.reminders, iface: 'DeadlineReminderCreateRequest' },
  { schema: 'CustomReminderCreateRequest', file: TYPES.reminders, iface: 'CustomReminderCreateRequest' },
  { schema: 'ReminderCreateData', file: TYPES.reminders, iface: 'ReminderCreateData' },
  { schema: 'ReminderListItemData', file: TYPES.reminders, iface: 'ReminderListItemData' },
  { schema: 'ReminderListData', file: TYPES.reminders, iface: 'ReminderListData' },
  { schema: 'ReminderStatusUpdateRequest', file: TYPES.reminders, iface: 'ReminderStatusUpdateRequest' },
  { schema: 'ReminderStatusUpdateData', file: TYPES.reminders, iface: 'ReminderStatusUpdateData' },
  { schema: 'ScheduleImportItemData', file: TYPES.reminders, iface: 'ScheduleImportItemData' },
  { schema: 'SchedulePreviewData', file: TYPES.reminders, iface: 'SchedulePreviewData' },
  { schema: 'ScheduleImportData', file: TYPES.reminders, iface: 'ScheduleImportData' },
  { schema: 'NotificationData', file: TYPES.notifications, iface: 'NotificationData' },
  { schema: 'NotificationListData', file: TYPES.notifications, iface: 'NotificationListData' },
  { schema: 'NotificationReadRequest', file: TYPES.notifications, iface: 'NotificationReadRequest' },
  { schema: 'NotificationReadData', file: TYPES.notifications, iface: 'NotificationReadData' },
  //  管理面：治理开关 → 配额 → 审计 → 用量 → 两个 keyset 列表体。
  { schema: 'AdminAiControls', file: TYPES.admin, iface: 'AdminAiControls' },
  { schema: 'KillSwitchState', file: TYPES.admin, iface: 'KillSwitchState' },
  { schema: 'ExperimentBudgetState', file: TYPES.admin, iface: 'ExperimentBudgetState' },
  { schema: 'UpdateAiControlsRequest', file: TYPES.admin, iface: 'UpdateAiControlsRequest' },
  { schema: 'AiQuota', file: TYPES.admin, iface: 'AiQuota' },
  { schema: 'UpdateAiQuotaRequest', file: TYPES.admin, iface: 'UpdateAiQuotaRequest' },
  { schema: 'AuditEventItem', file: TYPES.admin, iface: 'AuditEventItem' },
  { schema: 'UsageTotals', file: TYPES.admin, iface: 'UsageTotals' },
  { schema: 'UsageDayPoint', file: TYPES.admin, iface: 'UsageDayPoint' },
  { schema: 'UsageStatusCount', file: TYPES.admin, iface: 'UsageStatusCount' },
  { schema: 'UsageTopUser', file: TYPES.admin, iface: 'UsageTopUser' },
  { schema: 'UsageSummary', file: TYPES.admin, iface: 'UsageSummary' },
  { schema: 'AiQuotaListData', file: TYPES.admin, iface: 'AiQuotaListData' },
  { schema: 'AuditEventListData', file: TYPES.admin, iface: 'AuditEventListData' },
  // 契约 2.8.0 可观测性入口：只有一个基地址字段，空串 = 禁用。
  { schema: 'ObservabilityData', file: TYPES.admin, iface: 'ObservabilityData' },
  //  用户管理面：改密请求 → 列表行 → 两个写请求 → keyset 列表体。
  { schema: 'ChangePasswordRequest', file: TYPES.users, iface: 'ChangePasswordRequest' },
  { schema: 'UserSummaryData', file: TYPES.users, iface: 'UserSummary' },
  { schema: 'UserRolesUpdateRequest', file: TYPES.users, iface: 'UpdateUserRolesRequest' },
  { schema: 'UserStatusUpdateRequest', file: TYPES.users, iface: 'UpdateUserStatusRequest' },
  { schema: 'UserListData', file: TYPES.users, iface: 'UserListData' },
  // 契约 2.7.0 模型连接管理面：读侧形态 → 列表体 → 三个写请求 → 探测结论 → 预设项与清单体。
  { schema: 'ModelConnection', file: TYPES.modelConnections, iface: 'ModelConnection' },
  { schema: 'ModelConnectionListData', file: TYPES.modelConnections, iface: 'ModelConnectionListData' },
  { schema: 'CreateModelConnectionRequest', file: TYPES.modelConnections, iface: 'CreateModelConnectionRequest' },
  { schema: 'AuthorizeModelConnectionRequest', file: TYPES.modelConnections, iface: 'AuthorizeModelConnectionRequest' },
  { schema: 'UpdateActivationRequest', file: TYPES.modelConnections, iface: 'UpdateActivationRequest' },
  { schema: 'ModelProbeData', file: TYPES.modelConnections, iface: 'ModelProbeData' },
  { schema: 'ModelPreset', file: TYPES.modelConnections, iface: 'ModelPreset' },
  { schema: 'ModelPresetListData', file: TYPES.modelConnections, iface: 'ModelPresetListData' },
  // 契约 2.8.0 长期记忆管理面：读侧形态 → 列表体；管理面没有写请求。
  { schema: 'MemoryItem', file: TYPES.memories, iface: 'MemoryItem' },
  { schema: 'MemoryListData', file: TYPES.memories, iface: 'MemoryListData' },
];

const ENVELOPE_SCHEMAS = [
  'LoginEnvelope',
  'CurrentUserEnvelope',
  'UploadInitEnvelope',
  'CompleteUploadEnvelope',
  'DocumentStatusEnvelope',
  'AskEnvelope',
  'CreateConversationEnvelope',
  'ReminderCreateEnvelope',
  'DocumentListEnvelope',
  'ReminderListEnvelope',
  'MessageHistoryEnvelope',
  'ConversationListEnvelope',
  'UserEnvelope',
  'UserListEnvelope',
  'SchedulePreviewEnvelope',
  'ScheduleImportEnvelope',
  'ReminderStatusUpdateEnvelope',
  'NotificationListEnvelope',
  'NotificationReadEnvelope',
  'AdminAiControlsEnvelope',
  'KillSwitchStateEnvelope',
  'AiQuotaEnvelope',
  'AiQuotaListEnvelope',
  'AuditEventListEnvelope',
  'ObservabilityEnvelope',
  'UsageEnvelope',
  'ModelConnectionEnvelope',
  'ModelConnectionListEnvelope',
  'ModelProbeEnvelope',
  'ModelPresetListEnvelope',
  'MemoryListEnvelope',
];

/** 未镜像的契约 schema 必须在此显式列名并写原因；两边都没覆盖会让覆盖测试变红。 */
const NOT_MIRRORED: { schema: string; reason: string }[] = [
  {
    schema: 'CompleteUploadRequest',
    reason: '请求体只有 parts 一个数组字段，由 api/documents.ts 内联构造；字段集合由下方专项断言锁定。',
  },
  {
    schema: 'ScheduleImportRequest',
    reason:
      '契约唯一的 multipart 请求，三个 Part 没有 JSON 形态可镜像；Part 名、required 与 x-max-bytes 由下方专项断言锁定。',
  },
  ...ENVELOPE_SCHEMAS.map((schema) => ({
    schema,
    reason: `Envelope 是 ApiEnvelope + data 的 allOf 组合，前端由泛型 \`ApiEnvelope<T>\` 统一承载，逐个镜像会得到 ${ENVELOPE_SCHEMAS.length} 个同构接口。`,
  })),
  {
    schema: 'AskStreamMeta',
    reason:
      '事件帧顶层只有 event/data，载荷缩在 data 之下，进不了按顶层字段比对的镜像表；event 取值与 data 字段集合/required 由下方「四帧载荷」专项断言当场锁定，前端 AskStreamMetaPayload 已在  交付。',
  },
  {
    schema: 'AskStreamDelta',
    reason: '同 AskStreamMeta：嵌套载荷由下方专项断言锁定，前端 AskStreamDeltaPayload 已在  交付。',
  },
  {
    schema: 'AskStreamDone',
    reason:
      'data 为 AskData 的 allOf + traceId，前端以 `AskStreamDonePayload extends AskData` 复用既有 AskData 镜像；allOf 组成与新增成员由下方专项断言锁定。',
  },
  {
    schema: 'AskStreamError',
    reason:
      'data 直接 $ref ProblemDetail，复用既有镜像；该引用形态由下方专项断言锁定。前端不产出 error 事件——api/conversations.ts 把它映射成 ApiError 抛出，与首字节前的 JSON 拒绝同构。',
  },
];

const MIRRORED_ENUMS: { schema: string; file: string; type: string; listConst: string | null }[] = [
  { schema: 'UserRole', file: TYPES.api, type: 'UserRole', listConst: 'USER_ROLES' },
  { schema: 'UserStatus', file: TYPES.api, type: 'UserStatus', listConst: 'USER_STATUSES' },
  { schema: 'DocumentStatus', file: TYPES.documents, type: 'DocumentStatus', listConst: 'DOCUMENT_STATUSES' },
  { schema: 'VectorConsistency', file: TYPES.documents, type: 'VectorConsistency', listConst: 'VECTOR_CONSISTENCIES' },
  { schema: 'BudgetStatus', file: TYPES.conversations, type: 'BudgetStatus', listConst: null },
  { schema: 'ReminderKind', file: TYPES.reminders, type: 'ReminderKind', listConst: 'REMINDER_KINDS' },
  { schema: 'ReminderStatus', file: TYPES.reminders, type: 'ReminderStatus', listConst: 'REMINDER_STATUSES' },
  {
    schema: 'ScheduleImportAction',
    file: TYPES.reminders,
    type: 'ScheduleImportAction',
    listConst: 'SCHEDULE_IMPORT_ACTIONS',
  },
  {
    schema: 'ModelProbeFailure',
    file: TYPES.modelConnections,
    type: 'ModelProbeFailure',
    listConst: 'MODEL_PROBE_FAILURES',
  },
  {
    schema: 'ModelPresetGroup',
    file: TYPES.modelConnections,
    type: 'ModelPresetGroup',
    listConst: 'MODEL_PRESET_GROUPS',
  },
];

/**
 * 管理面的四个枚举没有独立 schema：契约把取值写在字段的内联 `enum:` 上，
 * 因此按「宿主 schema + 字段」定位而不是按 schema 名，其余口径与 `MIRRORED_ENUMS` 一致。
 * `UsageBucket` 没有列表常量：契约当前只发布 `day` 一个取值，前端把它收窄成单值类型，
 * 界面上没有可枚举的选择器，造一个只有元素的数组反而会被误用成「以后可以选」。
 */
const MIRRORED_INLINE_ENUMS: {
  schema: string;
  field: string;
  file: string;
  type: string;
  listConst: string | null;
}[] = [
  { schema: 'AuditEventItem', field: 'source', file: TYPES.admin, type: 'AuditSource', listConst: 'AUDIT_SOURCES' },
  { schema: 'AuditEventItem', field: 'severity', file: TYPES.admin, type: 'AuditSeverity', listConst: 'AUDIT_SEVERITIES' },
  { schema: 'UsageStatusCount', field: 'status', file: TYPES.admin, type: 'UsageStatus', listConst: 'USAGE_STATUSES' },
  { schema: 'UsageSummary', field: 'bucket', file: TYPES.admin, type: 'UsageBucket', listConst: null },
  {
    schema: 'ModelConnection',
    field: 'probeStatus',
    file: TYPES.modelConnections,
    type: 'ProbeStatus',
    listConst: 'PROBE_STATUSES',
  },
];

/** 前端把契约的数值约束抄成常量，这里逐个回比。 */
const MIRRORED_CONSTRAINTS: { schema: string; field: string; keyword: string; file: string; constName: string }[] = [
  { schema: 'RegisterRequest', field: 'username', keyword: 'minLength', file: TYPES.api, constName: 'REGISTER_USERNAME_MIN_LENGTH' },
  { schema: 'RegisterRequest', field: 'username', keyword: 'maxLength', file: TYPES.api, constName: 'REGISTER_USERNAME_MAX_LENGTH' },
  { schema: 'RegisterRequest', field: 'password', keyword: 'minLength', file: TYPES.api, constName: 'PASSWORD_MIN_LENGTH' },
  { schema: 'RegisterRequest', field: 'password', keyword: 'maxLength', file: TYPES.api, constName: 'PASSWORD_MAX_LENGTH' },
  { schema: 'DeadlineReminderCreateRequest', field: 'title', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TITLE_MAX_LENGTH' },
  { schema: 'DeadlineReminderCreateRequest', field: 'description', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_DESCRIPTION_MAX_LENGTH' },
  { schema: 'DeadlineReminderCreateRequest', field: 'timezone', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TIMEZONE_MAX_LENGTH' },
  { schema: 'DeadlineReminderCreateRequest', field: 'leadMinutes', keyword: 'minimum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MIN' },
  { schema: 'DeadlineReminderCreateRequest', field: 'leadMinutes', keyword: 'maximum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MAX' },
  // 读侧曾经把 ceiling 写成 1440，与写入面的 10080 不一致；这一条是那次漂移的永久防复发。
  { schema: 'ReminderListItemData', field: 'leadMinutes', keyword: 'minimum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MIN' },
  { schema: 'ReminderListItemData', field: 'leadMinutes', keyword: 'maximum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MAX' },
  { schema: 'CustomReminderCreateRequest', field: 'rrule', keyword: 'minLength', file: TYPES.reminders, constName: 'RRULE_MIN_LENGTH' },
  { schema: 'CustomReminderCreateRequest', field: 'rrule', keyword: 'maxLength', file: TYPES.reminders, constName: 'RRULE_MAX_LENGTH' },
  { schema: 'CustomReminderCreateRequest', field: 'exdates', keyword: 'maxItems', file: TYPES.reminders, constName: 'EXDATES_MAX' },
  { schema: 'AskRequest', field: 'question', keyword: 'maxLength', file: TYPES.conversations, constName: 'QUESTION_MAX_LENGTH' },
  { schema: 'AskRequest', field: 'documentIds', keyword: 'maxItems', file: TYPES.conversations, constName: 'DOCUMENT_IDS_MAX' },
  { schema: 'CreateConversationRequest', field: 'title', keyword: 'maxLength', file: TYPES.conversations, constName: 'TITLE_MAX_LENGTH' },
  { schema: 'DocumentRenameRequest', field: 'title', keyword: 'maxLength', file: TYPES.documents, constName: 'DOCUMENT_TITLE_MAX_LENGTH' },
  { schema: 'DocumentTagsUpdateRequest', field: 'tags', keyword: 'maxItems', file: TYPES.documents, constName: 'DOCUMENT_TAGS_MAX' },
  { schema: 'ScheduleImportRequest', field: 'fallbackTimezone', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TIMEZONE_MAX_LENGTH' },
  { schema: 'ScheduleImportRequest', field: 'leadMinutes', keyword: 'minimum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MIN' },
  { schema: 'ScheduleImportRequest', field: 'leadMinutes', keyword: 'maximum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MAX' },
  { schema: 'SchedulePreviewData', field: 'fallbackTimezone', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TIMEZONE_MAX_LENGTH' },
  { schema: 'SchedulePreviewData', field: 'leadMinutes', keyword: 'maximum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MAX' },
  { schema: 'ScheduleImportData', field: 'fallbackTimezone', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TIMEZONE_MAX_LENGTH' },
  { schema: 'ScheduleImportData', field: 'leadMinutes', keyword: 'maximum', file: TYPES.reminders, constName: 'LEAD_MINUTES_MAX' },
  { schema: 'SchedulePreviewData', field: 'itemCount', keyword: 'maximum', file: TYPES.reminders, constName: 'SCHEDULE_EVENT_MAX' },
  { schema: 'ScheduleImportData', field: 'itemCount', keyword: 'maximum', file: TYPES.reminders, constName: 'SCHEDULE_EVENT_MAX' },
  { schema: 'ScheduleImportItemData', field: 'summary', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TITLE_MAX_LENGTH' },
  { schema: 'ScheduleImportItemData', field: 'description', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_DESCRIPTION_MAX_LENGTH' },
  { schema: 'ScheduleImportItemData', field: 'timezone', keyword: 'maxLength', file: TYPES.reminders, constName: 'REMINDER_TIMEZONE_MAX_LENGTH' },
  { schema: 'ScheduleImportItemData', field: 'rrule', keyword: 'maxLength', file: TYPES.reminders, constName: 'RRULE_MAX_LENGTH' },
  { schema: 'ScheduleImportItemData', field: 'exdates', keyword: 'maxItems', file: TYPES.reminders, constName: 'EXDATES_MAX' },
  { schema: 'NotificationData', field: 'title', keyword: 'maxLength', file: TYPES.notifications, constName: 'NOTIFICATION_TITLE_MAX_LENGTH' },
  { schema: 'NotificationData', field: 'description', keyword: 'maxLength', file: TYPES.notifications, constName: 'NOTIFICATION_DESCRIPTION_MAX_LENGTH' },
  { schema: 'NotificationReadRequest', field: 'ids', keyword: 'maxItems', file: TYPES.notifications, constName: 'NOTIFICATION_IDS_MAX' },
  // 配额的两个上限：读侧 AiQuota 与写侧 UpdateAiQuotaRequest 在契约上共用同一组数值，
  // 只镜像一侧就会让「编辑表单的校验」与「列表的显示」漂移，因此四个宿主都指向同一对常量。
  { schema: 'AiQuota', field: 'dailyQuestionLimit', keyword: 'minimum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MIN' },
  { schema: 'AiQuota', field: 'dailyQuestionLimit', keyword: 'maximum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MAX' },
  { schema: 'AiQuota', field: 'dailyTokenLimit', keyword: 'minimum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MIN' },
  { schema: 'AiQuota', field: 'dailyTokenLimit', keyword: 'maximum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MAX' },
  { schema: 'UpdateAiQuotaRequest', field: 'dailyQuestionLimit', keyword: 'minimum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MIN' },
  { schema: 'UpdateAiQuotaRequest', field: 'dailyQuestionLimit', keyword: 'maximum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MAX' },
  { schema: 'UpdateAiQuotaRequest', field: 'dailyTokenLimit', keyword: 'minimum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MIN' },
  { schema: 'UpdateAiQuotaRequest', field: 'dailyTokenLimit', keyword: 'maximum', file: TYPES.admin, constName: 'QUOTA_LIMIT_MAX' },
  // 三个列表/榜单的 maxItems 与对应查询参数的 maximum 同值：契约两侧各写一遍，
  // 前端只镜像一个常量，任一侧改动都会在这里变红。
  { schema: 'AiQuotaListData', field: 'items', keyword: 'maxItems', file: TYPES.admin, constName: 'QUOTA_LIST_LIMIT_MAX' },
  { schema: 'AuditEventListData', field: 'items', keyword: 'maxItems', file: TYPES.admin, constName: 'AUDIT_QUERY_LIMIT_MAX' },
  { schema: 'UsageSummary', field: 'topUsers', keyword: 'maxItems', file: TYPES.admin, constName: 'USAGE_TOP_USERS_MAX' },
  // 改密的旧口令只约束长度（契约没有 pattern，缺或错统一 401），新口令复用注册策略那一对常量——
  // 契约原话是「必须满足与注册相同的 PasswordPolicy」，另立一份就会在策略调整时漂移。
  { schema: 'ChangePasswordRequest', field: 'currentPassword', keyword: 'minLength', file: TYPES.users, constName: 'CURRENT_PASSWORD_MIN_LENGTH' },
  { schema: 'ChangePasswordRequest', field: 'currentPassword', keyword: 'maxLength', file: TYPES.users, constName: 'CURRENT_PASSWORD_MAX_LENGTH' },
  { schema: 'ChangePasswordRequest', field: 'newPassword', keyword: 'minLength', file: TYPES.api, constName: 'PASSWORD_MIN_LENGTH' },
  { schema: 'ChangePasswordRequest', field: 'newPassword', keyword: 'maxLength', file: TYPES.api, constName: 'PASSWORD_MAX_LENGTH' },
  { schema: 'UserListData', field: 'items', keyword: 'maxItems', file: TYPES.users, constName: 'USER_LIST_LIMIT_MAX' },
  // 长期记忆：文本上限与问答 question 同源；条数上限就是数据面每用户淘汰阈值。
  { schema: 'MemoryItem', field: 'text', keyword: 'maxLength', file: TYPES.memories, constName: 'MEMORY_TEXT_MAX_LENGTH' },
  { schema: 'MemoryListData', field: 'items', keyword: 'maxItems', file: TYPES.memories, constName: 'MEMORY_FACTS_MAX' },
];

/** 数组项级约束的镜像位置，缩进与字段级不同，因此单独成表。 */
const MIRRORED_ITEM_CONSTRAINTS: {
  schema: string;
  field: string;
  keyword: string;
  file: string;
  constName: string;
}[] = [
  { schema: 'DocumentTagsUpdateRequest', field: 'tags', keyword: 'maxLength', file: TYPES.documents, constName: 'DOCUMENT_TAG_MAX_LENGTH' },
];

/**
 * 查询参数级约束的镜像位置：前端按上限在提交前修剪，
 * 而不是把超长值交给服务端换一次 400 `COM-VALIDATION-001`。
 * 管理面的三个数量参数是**独立参数**而不是放宽全局 `PageLimit`（契约刻意如此），
 * 所以它们与上面 `AiQuotaListData.items` / `AuditEventListData.items` / `UsageSummary.topUsers`
 * 的 `maxItems` 各成一行，同一个前端常量被两个宿主回比。
 */
const MIRRORED_PARAMETER_CONSTRAINTS: {
  parameter: string;
  keyword: string;
  file: string;
  constName: string;
}[] = [
  { parameter: 'DocumentSearchQuery', keyword: 'maxLength', file: TYPES.documents, constName: 'DOCUMENT_QUERY_MAX_LENGTH' },
  { parameter: 'DocumentTagFilter', keyword: 'maxLength', file: TYPES.documents, constName: 'DOCUMENT_TAG_FILTER_MAX_LENGTH' },
  { parameter: 'QuotaListLimit', keyword: 'maximum', file: TYPES.admin, constName: 'QUOTA_LIST_LIMIT_MAX' },
  { parameter: 'AuditQueryLimit', keyword: 'maximum', file: TYPES.admin, constName: 'AUDIT_QUERY_LIMIT_MAX' },
  { parameter: 'UsageTopUsers', keyword: 'maximum', file: TYPES.admin, constName: 'USAGE_TOP_USERS_MAX' },
  // 用户列表用的是**全局** `PageLimit`（上限 100，与 `UserListData.items` 的 maxItems 同值），
  // 这一行对的是「上限」；各 store 的 PAGE_LIMIT 是否等于契约**默认值**由下方专门断言逐 store 回比。
  { parameter: 'PageLimit', keyword: 'maximum', file: TYPES.users, constName: 'USER_LIST_LIMIT_MAX' },
];

/**
 * 前端比契约多出一个字段的位置。
 * `ApiEnvelope.data` 是唯一一处：契约的通用信封只有 `code/msg/traceId` 三个属性，
 * `data` 的必填由各 Envelope 的 `allOf` 声明；前端用泛型 `ApiEnvelope<T>` 承载时
 * 把 `data: T` 提前固定，消费方因此永远拿得到 data，而不是在每个同构 Envelope 接口里各补一次。
 */
const FIELD_SET_EXCEPTIONS: { schema: string; extraInFrontend: string[]; reason: string }[] = [
  { schema: 'ApiEnvelope', extraInFrontend: ['data'], reason: 'data 由各 Envelope 的 allOf 声明为必填。' },
];

/**
 * 前端比契约更严（TS 必填、契约未列入 `required`）的位置，全部已核实并登记原因。
 *
 * 两类：
 * 1. `nextCursor` / `*At` / `errorCode` 等「契约允许整个键缺省、值可为 null」的响应字段。
 *    后端这些 DTO 一律是 Java record，且仓库内没有任何 Jackson 排除 null 的装配
 *    （`@JsonInclude` / `default-property-inclusion` 命中 0，由下方专项断言机器化守住），
 *    因此实际线上形态是「键恒在、值可为 null」，前端按这个形态镜像，消费方不必到处写可选链。
 * 2. `ApiEnvelope.data`：契约的通用信封只有 `code/msg/traceId`，`data` 的必填交给各 Envelope 的 `allOf`。
 *
 * 契约一旦把这些字段列入 `required`，或后端引入了 null 排除配置，这两处都会变红——这正是登记的意义。
 *
 * 管理面两个 keyset 列表体（`AiQuotaListData` / `AuditEventListData`）**不在**此列：
 * 契约把它们的 `nextCursor` 直接写进了 `required`，与面向用户的 `*ListData` 口径不同。
 * 这一处差异是契约事实而不是前端裁决，因此不需要登记例外，但值得在此留一行，
 * 免得下一次有人把管理端列表也补进这张表来「求统一」。
 */
const ALLOWED_STRICTER: Record<string, string[]> = {
  ApiEnvelope: ['data'],
  UploadInitData: ['expiresAt'],
  DocumentStatusData: [
    'errorCode',
    'errorMessage',
    'indexedAt',
    'pendingIndexVersion',
    'vectorRows',
    'vectorConfirmedAt',
  ],
  DocumentListItemData: ['errorCode', 'indexedAt'],
  DocumentListData: ['nextCursor'],
  ConversationListItemData: ['lastMessageAt'],
  ConversationListData: ['nextCursor'],
  MessageHistoryData: ['nextCursor'],
  ReminderListItemData: ['description', 'lastTriggeredAt'],
  ReminderListData: ['nextCursor'],
  ReminderStatusUpdateData: ['nextTriggerAt'],
  NotificationData: ['description', 'nextTriggerAt', 'readAt'],
  NotificationListData: ['nextCursor'],
  ScheduleImportItemData: ['description'],
  KillSwitchState: ['updatedAt', 'updatedBy'],
  ExperimentBudgetState: ['callLimit', 'tokenLimit'],
  AiQuota: ['username', 'updatedBy'],
  AuditEventItem: ['agentName'],
  UsageTotals: ['avgLatencyMs'],
  UsageTopUser: ['username'],
};

/**
 * 时区样本，供所有承载同一契约 pattern 的字段复用。
 * 契约的 `timezone` 与 `fallbackTimezone` 三条 pattern 逐字节相同，因此一份样本
 * 同时验证两个宿主；抽成常量而不是复制两遍，是为了让「新增一个时区字段」
 * 变成改一处即可的事，也避免两份样本各自漂移后失去对账意义。
 */
const TIMEZONE_SAMPLES: string[] = [
  'UTC',
  'Asia/Shanghai',
  'Europe/London',
  'America/Argentina/Buenos_Aires',
  'Etc/GMT+8',
  'utc',
  'Shanghai',
  'Asia/Shang hai',
  '/Asia',
  'Asia/',
  '',
];

/**
 * 审计 `traceId` 样本：契约要求 32 位**小写**十六进制，与问答响应信封里的 `traceId` 同形。
 * 大写、长短一位、带连字符的 UUID 形态都必须判假——第三种是有人把 `userId` 粘进 traceId 输入框的样子。
 */
const AUDIT_TRACE_ID_SAMPLES: string[] = [
  '4bf92f3577b34da6a3ce929d0e0e4736',
  '0'.repeat(32),
  '4BF92F3577B34DA6A3CE929D0E0E4736',
  '4bf92f3577b34da6a3ce929d0e0e473',
  '4bf92f3577b34da6a3ce929d0e0e47366',
  '4bf92f3577b34da6a3ce929d0e0e473g',
  '0192f3a4-9d4e-7f32-8a01-1234567890ab',
  '',
];

/**
 * 审计 `eventType` 样本：大写字母开头，其后仅允许大写字母/数字/下划线，总长 ≤ 64。
 * `COM-VALIDATION-001` 判假是有意为之——错误码不是事件类型，把两者混进同一个输入框会得到 400。
 */
const AUDIT_EVENT_TYPE_SAMPLES: string[] = [
  'RAG_RETRIEVED',
  'A',
  'A'.repeat(64),
  'A'.repeat(65),
  'rag_retrieved',
  '2FA_DONE',
  '_INTERNAL',
  'COM-VALIDATION-001',
  'RAG RETRIEVED',
  '',
];

/**
 * 口令策略样本。契约把同一条 pattern 写在两处（`RegisterRequest.password` 与
 * `ChangePasswordRequest.newPassword`，后者原话是「必须满足与注册相同的 `PasswordPolicy`」），
 * 前端也只镜像 `PASSWORD_PATTERN` 一个常量，因此一份样本同时回比两个宿主。
 */
const PASSWORD_POLICY_SAMPLES: string[] = [
  'goodpass123',
  'CorrectHorse9',
  'abcdefghij',
  '1234567890',
  'short1',
  'A1'.repeat(36),
  'A1'.repeat(37),
  '',
];

/** 宿主 schema 与承载 pattern 的字段。 */
const PATTERN_HOSTS: { schema: string; field: string; file: string; constName: string; samples: string[] }[] = [
  {
    schema: 'RegisterRequest',
    field: 'username',
    file: TYPES.api,
    constName: 'REGISTER_USERNAME_PATTERN',
    samples: [
      'abc',
      'zhang.wei',
      'demo_user-1',
      'A'.repeat(32),
      'ab',
      'A'.repeat(33),
      'zhang wei',
      '张三',
      'user@host',
      '',
    ],
  },
  {
    schema: 'RegisterRequest',
    field: 'password',
    file: TYPES.api,
    constName: 'PASSWORD_PATTERN',
    samples: PASSWORD_POLICY_SAMPLES,
  },
  {
    // 改密的新口令与注册口令共用同一个前端常量：契约的 pattern 若在两处之一被改，这一条会先红，
    // 而界面（账户页改密表单）的本地预检与注册预检仍只有一处真相。
    schema: 'ChangePasswordRequest',
    field: 'newPassword',
    file: TYPES.api,
    constName: 'PASSWORD_PATTERN',
    samples: PASSWORD_POLICY_SAMPLES,
  },
  {
    schema: 'DeadlineReminderCreateRequest',
    field: 'timezone',
    file: TYPES.reminders,
    constName: 'TIMEZONE_PATTERN',
    samples: TIMEZONE_SAMPLES,
  },
  {
    // 兜底时区与手工创建的 timezone 复用同一个前端常量：契约只改 pattern 的话，这一条会先红。
    schema: 'ScheduleImportRequest',
    field: 'fallbackTimezone',
    file: TYPES.reminders,
    constName: 'TIMEZONE_PATTERN',
    samples: TIMEZONE_SAMPLES,
  },
  {
    schema: 'CustomReminderCreateRequest',
    field: 'rrule',
    file: TYPES.reminders,
    constName: 'RRULE_PATTERN',
    samples: [
      'FREQ=WEEKLY;BYDAY=MO',
      'FREQ=SECONDLY',
      'FREQ=WEEKLY;UNTIL=20261231T235959Z',
      'FREQ=WEEKLY;INTERVAL=2;BYMONTHDAY=1,15',
      'FREQ=MINUTELY;',
      'WEEKLY',
      'freq=WEEKLY',
      'FREQ=HOURLY;;INTERVAL=2',
      'FREQ=WEEKLY;byday=MO',
      '',
    ],
  },
  {
    // 读侧条目与查询参数 `AuditTraceId` 共用同一常量；两者在契约上是两处独立 pattern，
    // 由下方「审计查询参数与读侧镜像同形」那条逐字节对齐，所以这里只需回比一个宿主。
    schema: 'AuditEventItem',
    field: 'traceId',
    file: TYPES.admin,
    constName: 'AUDIT_TRACE_ID_PATTERN',
    samples: AUDIT_TRACE_ID_SAMPLES,
  },
  {
    // 同上：`AuditEventType` 查询参数与 `AuditEventItem.eventType` 共用常量，
    // 审计表单的本地预拦也用它，契约改 pattern 时三条路径一起变红。
    schema: 'AuditEventItem',
    field: 'eventType',
    file: TYPES.admin,
    constName: 'AUDIT_EVENT_TYPE_PATTERN',
    samples: AUDIT_EVENT_TYPE_SAMPLES,
  },
];

describe('契约 ↔ 前端类型对账', () => {
  it('accounts for every schema in the contract, mirrored or explicitly waived', () => {
    const accounted = new Set<string>([
      ...MIRRORED_OBJECTS.map((entry) => entry.schema),
      ...NOT_MIRRORED.map((entry) => entry.schema),
      ...MIRRORED_ENUMS.map((entry) => entry.schema),
      'ReminderCreateRequest',
    ]);
    const unaccounted = SCHEMA_NAMES.filter((name) => !accounted.has(name));
    const stale = [...accounted].filter((name) => !SCHEMA_NAMES.includes(name));

    expect(unaccounted, `契约新增/改名的 schema 未经裁决：${unaccounted.join(', ')}`).toEqual([]);
    expect(stale, `前端镜像表里的 schema 在契约中已不存在：${stale.join(', ')}`).toEqual([]);
    // 契约 schema 计数（2.9.0 的 118 个 + 人在回路 PendingApproval 1 个）。契约一旦增删，这一条先红，逼着来做一次裁决。
    expect(SCHEMA_NAMES).toHaveLength(119);
  });

  it('mirrors every object schema field-for-field', () => {
    const problems: string[] = [];
    for (const entry of MIRRORED_OBJECTS) {
      const contract = contractFields(SCHEMAS.get(entry.schema) ?? []).slice().sort();
      const declared = tsFieldNames(entry.file, entry.iface).sort();
      const allowed = (FIELD_SET_EXCEPTIONS.find((item) => item.schema === entry.schema)?.extraInFrontend ?? [])
        .slice()
        .sort();
      const expected = contract.concat(allowed).sort();
      if (expected.join('|') !== declared.join('|')) {
        problems.push(
          `${entry.schema} ↔ ${entry.iface}：期望 [${expected.join(', ')}]｜实际 [${declared.join(', ')}]` +
            `（契约字段 [${contract.join(', ')}]｜登记多出 [${allowed.join(', ')}]）`,
       );
      }
    }
    expect(problems).toEqual([]);
  });

  it('never lets the front-end soften a field the contract requires', () => {
    const problems: string[] = [];
    for (const entry of MIRRORED_OBJECTS) {
      const required = new Set(contractRequired(SCHEMAS.get(entry.schema) ?? []));
      const ts = tsMembers(entry.file, entry.iface);
      const softened = ts.optional.filter((name) => required.has(name));
      const stricter = ts.required
        .filter((name) => !required.has(name))
        .filter((name) => !(ALLOWED_STRICTER[entry.schema] ?? []).includes(name));
      if (softened.length > 0) {
        problems.push(`${entry.schema}.${softened.join(', ')} 契约必填、TS 却可选（可能提交出缺字段的请求）`);
      }
      if (stricter.length > 0) {
        problems.push(`${entry.schema}.${stricter.join(', ')} TS 必填但契约未列入 required 且未登记原因`);
      }
    }
    expect(problems).toEqual([]);
  });

  it('keeps enum mirrors equal to the contract in both directions', () => {
    for (const entry of MIRRORED_ENUMS) {
      const contract = contractEnum(SCHEMAS.get(entry.schema) ?? []).slice().sort();
      const ts = tsLiterals(entry.file, entry.type, 'type').slice().sort();
      expect(ts, `${entry.type} 与契约 ${entry.schema} 枚举不一致`).toEqual(contract);
      if (entry.listConst !== null) {
        const list = tsLiterals(entry.file, entry.listConst, 'const').slice().sort();
        expect(list, `${entry.listConst} 与契约 ${entry.schema} 不一致`).toEqual(contract);
      }
    }
    for (const entry of MIRRORED_INLINE_ENUMS) {
      const contract = contractFieldEnum(entry.schema, entry.field).slice().sort();
      const ts = tsLiterals(entry.file, entry.type, 'type').slice().sort();
      expect(ts, `${entry.type} 与契约内联枚举 ${entry.schema}.${entry.field} 不一致`).toEqual(contract);
      if (entry.listConst !== null) {
        const list = tsLiterals(entry.file, entry.listConst, 'const').slice().sort();
        expect(list, `${entry.listConst} 与契约 ${entry.schema}.${entry.field} 不一致`).toEqual(contract);
      }
    }
  });

  /**
   * `ReminderKind` 读侧三值、创建侧两值，`COURSE` 只能由 ICS 导入产生。
   * 这条差集是界面上「类型单选不含课程提醒」的唯一依据，因此创建侧全集当场从契约的
   * `discriminator.mapping` 解析，而不是把 DEADLINE/CUSTOM 抄成期望值。
   */
  it('keeps the create-side kinds narrower than the read-side kinds', () => {
    const createKinds = contractDiscriminator(SCHEMAS.get('ReminderCreateRequest') ?? []).sort();
    expect(createKinds, '前端 ReminderCreateKind 与契约创建侧判别映射不一致').toEqual(
      tsLiterals(TYPES.reminders, 'ReminderCreateKind', 'type').slice().sort(),
   );
    expect(createKinds, '前端 REMINDER_CREATE_KINDS 与契约创建侧判别映射不一致').toEqual(
      tsLiterals(TYPES.reminders, 'REMINDER_CREATE_KINDS', 'const').slice().sort(),
   );

    const readKinds = contractEnum(SCHEMAS.get('ReminderKind') ?? []).sort();
    expect(
      readKinds.filter((kind) => !createKinds.includes(kind)),
      '读侧相对创建侧多出的类型应恰好只有 COURSE；契约若改了口径，界面文案要一起裁决',
   ).toEqual(['COURSE']);

    // 两个读侧 kind 都复用三值 ReminderKind；契约若把它们改成内联枚举，上面的差集断言就失去依据。
    for (const schema of ['ReminderCreateData', 'NotificationData']) {
      const body = SCHEMAS.get(schema) ?? [];
      const at = body.findIndex((line) => line === '        kind:');
      expect(at, `契约 ${schema} 缺少 kind 字段`).toBeGreaterThanOrEqual(0);
      expect(body[at + 1]?.trim(), `契约 ${schema}.kind 应复用三值 ReminderKind`).toBe(
        "$ref: '#/components/schemas/ReminderKind'",
     );
    }
  });

  it('copies numeric and length constraints without drift', () => {
    for (const entry of MIRRORED_CONSTRAINTS) {
      const fromContract = Number(contractConstraint(entry.schema, entry.field, entry.keyword));
      const inFrontend = tsNumberConst(entry.file, entry.constName);
      expect(
        inFrontend,
        `${entry.constName} 应等于契约 ${entry.schema}.${entry.field} 的 ${entry.keyword}`,
     ).toBe(fromContract);
    }
    for (const entry of MIRRORED_ITEM_CONSTRAINTS) {
      const fromContract = Number(contractItemConstraint(entry.schema, entry.field, entry.keyword));
      const inFrontend = tsNumberConst(entry.file, entry.constName);
      expect(
        inFrontend,
        `${entry.constName} 应等于契约 ${entry.schema}.${entry.field} 数组项的 ${entry.keyword}`,
     ).toBe(fromContract);
    }
    for (const entry of MIRRORED_PARAMETER_CONSTRAINTS) {
      const fromContract = Number(parameterConstraint(entry.parameter, entry.keyword));
      const inFrontend = tsNumberConst(entry.file, entry.constName);
      expect(
        inFrontend,
        `${entry.constName} 应等于契约参数 ${entry.parameter} 的 ${entry.keyword}`,
     ).toBe(fromContract);
    }
  });

  it('keeps the two pattern mirrors behaviourally identical to the contract', () => {
    for (const host of PATTERN_HOSTS) {
      // 不做字符串比对：契约保留了 `\-` 这类冗余转义，而 ESLint 禁止 TS 里出现它。
      const contractRe = new RegExp(contractConstraint(host.schema, host.field, 'pattern'));
      const frontendRe = tsRegExp(host.file, host.constName);
      const disagreeing = host.samples.filter((sample) => contractRe.test(sample) !== frontendRe.test(sample));
      expect(disagreeing, `${host.constName} 与契约模式对样本 ${disagreeing.join(' / ')} 判定不一致`).toEqual([]);
    }
  });

  /**
   *  的四帧协议（`meta`/`delta`/`done`/`error`）载荷是 `{event, data}` 嵌套形态，
   * 顶层字段只有两个，进不了按顶层字段比对的 `MIRRORED_OBJECTS`；这里当场从契约解析出
   * 事件名、顶层 required 与 `data` 载荷，逐帧比对前端 `AskStream*Payload`。
   */
  it('mirrors the four ask stream frames on their nested data payloads', () => {
    const frames: { schema: string; event: string }[] = [
      { schema: 'AskStreamMeta', event: 'meta' },
      { schema: 'AskStreamDelta', event: 'delta' },
      { schema: 'AskStreamDone', event: 'done' },
      { schema: 'AskStreamError', event: 'error' },
    ];
    for (const frame of frames) {
      const body = SCHEMAS.get(frame.schema) ?? [];
      expect(contractFields(body), `${frame.schema} 的顶层字段应只有 event 与 data`).toEqual(['event', 'data']);
      expect(contractRequired(body), `${frame.schema} 的顶层 required`).toEqual(['event', 'data']);
      expect(frameEventEnum(frame.schema), `${frame.schema} 的事件名`).toEqual([frame.event]);
    }

    for (const entry of [
      { schema: 'AskStreamMeta', iface: 'AskStreamMetaPayload' },
      { schema: 'AskStreamDelta', iface: 'AskStreamDeltaPayload' },
    ]) {
      const dataBody = frameDataBody(entry.schema);
      const contract = frameDataFields(dataBody).sort();
      // 契约把载荷字段全部列为 required；前端任何一处写成可选都会在下一条断言里变红。
      expect(frameDataRequired(dataBody).sort(), `${entry.schema} 的 data 应逐字段必填`).toEqual(contract);
      expect(tsFieldNames(TYPES.conversations, entry.iface).sort(), `${entry.iface} 与契约 data 不一致`).toEqual(contract);
      expect(tsMembers(TYPES.conversations, entry.iface).optional, `${entry.iface} 不得出现可选载荷字段`).toEqual([]);
    }

    const done = frameDataBody('AskStreamDone');
    expect(done[0]?.trim(), 'done 帧的 data 应是 allOf 组合').toBe('allOf:');
    expect(done[1]?.trim(), 'done 帧的 data 第一支必须复用契约 AskData，前端 extends 的镜像才成立').toBe(
      "- $ref: '#/components/schemas/AskData'",
   );
    const doneExtra = done
      .filter((line) => /^ {16}[a-zA-Z][A-Za-z0-9_]*:$/.test(line))
      .map((line) => line.trim().replace(/:$/, ''));
    expect(doneExtra, 'done 帧相对 AskData 只应新增一个 traceId').toEqual(['traceId']);
    expect(tsFieldNames(TYPES.conversations, 'AskStreamDonePayload'), 'AskStreamDonePayload 只应 extends 之外多一个 traceId').toEqual([
      'traceId',
    ]);
    // AskData 若自己加上了 traceId，extends + 同名成员就成了重复声明，这一条会先把裁决逼出来。
    expect(contractFields(SCHEMAS.get('AskData') ?? []), 'AskData 不应已有 traceId').not.toContain('traceId');

    expect(frameDataBody('AskStreamError')[0]?.trim(), 'error 帧的 data 必须直接复用 ProblemDetail 镜像').toBe(
      "$ref: '#/components/schemas/ProblemDetail'",
   );
    // error 帧不进入事件联合：api 层把它映射成 ApiError 抛出，store 只需一个失败分支。
    const union = /export type AskStreamEvent =[\s\S]*?\};/.exec(tsText(TYPES.conversations))?.[0] ?? '';
    expect(
      [...union.matchAll(/kind: '([a-z]+)'/g)].map((match) => match[1] ?? '').sort(),
      'AskStreamEvent 应恰好覆盖 meta/delta/done；error 帧由 api 层抛出，不是可收事件',
   ).toEqual(['delta', 'done', 'meta']);
  });

  it('submits the complete-upload body with exactly the contract field', () => {
    const contract = contractFields(SCHEMAS.get('CompleteUploadRequest') ?? []);
    expect(contract).toEqual(['parts']);
    expect(contractConstraint('CompleteUploadRequest', 'parts', 'maxItems')).toBe('10');
  });

  it('keeps the multipart schedule request on the contract parts and byte ceiling', () => {
    const parts = contractFields(SCHEMAS.get('ScheduleImportRequest') ?? []);
    expect(parts).toEqual(['file', 'fallbackTimezone', 'leadMinutes']);
    // 三个 Part 全属必需：契约上缺任何一个都是 400 `COM-VALIDATION-001`，`errors[].field` 即那个 Part 名。
    expect(contractRequired(SCHEMAS.get('ScheduleImportRequest') ?? [])).toEqual(parts);

    const appended = [...tsText('src/api/reminders.ts').matchAll(/data\.append\('([^']+)'/g)].map((m) => m[1] ?? '');
    expect(appended, '前端提交的 Part 名与契约不一致，服务端会按缺失 Part 报 400').toEqual(parts);

    // 本地预拦读的是 `x-max-bytes` 这个机器可读键，不是 description 里的「2 MiB」人话。
    expect(tsNumberConst('src/types/reminders.ts', 'ICS_MAX_BYTES')).toBe(
      Number(contractConstraint('ScheduleImportRequest', 'file', 'x-max-bytes')),
   );
  });

  it('sends a page size the contract parameter actually allows', () => {
    const pageLimit = PARAMETER_BLOCKS.find((block) => block.name === 'PageLimit');
    expect(pageLimit).toBeDefined();
    const body = (pageLimit?.body ?? []).join('\n');
    const readNumber = (keyword: string) => Number(new RegExp(`${keyword}: (\\d+)`).exec(body)?.[1]);
    const stores = [
      'src/stores/documents.ts',
      'src/stores/conversations.ts',
      'src/stores/reminders.ts',
      'src/stores/notifications.ts',
      'src/stores/adminUsers.ts',
    ];

    expect(readNumber('default')).toBe(20);
    // PAGE_LIMIT 已收敛到 failureFields.ts 单一来源；逐个 store 只校验确实从该模块导入，
    // 避免常量漂移时 6 份副本各改一半（P1-3 竞态修复同时抽掉了样板）。
    const sharedPageLimit = tsNumberConst('src/stores/failureFields.ts', 'PAGE_LIMIT');
    expect(sharedPageLimit).toBe(readNumber('default'));
    expect(sharedPageLimit).toBeGreaterThanOrEqual(readNumber('minimum'));
    expect(sharedPageLimit).toBeLessThanOrEqual(readNumber('maximum'));
    for (const store of stores) {
      expect(readFileSync(store, 'utf8'), `${store} 应从 failureFields.ts 复用 PAGE_LIMIT`).toMatch(
        /import\s*\{[^}]*PAGE_LIMIT[^}]*\}\s*from\s*'\.\/failureFields'/,
     );
    }

    /**
     * 管理面两个列表各有**自己的** limit 参数（契约刻意不放宽全局 `PageLimit`，
     * 以免普通列表也接受 200 条），所以按各自的参数默认值回比，不能塞进上面那条 20 的断言。
     */
    const adminStores = [
      { store: 'src/stores/adminQuotas.ts', parameter: 'QuotaListLimit' },
      { store: 'src/stores/adminAudit.ts', parameter: 'AuditQueryLimit' },
    ];
    for (const entry of adminStores) {
      const block = PARAMETER_BLOCKS.find((item) => item.name === entry.parameter);
      expect(block, `契约缺少参数 ${entry.parameter}`).toBeDefined();
      const parameterBody = (block?.body ?? []).join('\n');
      const readFromParameter = (keyword: string) =>
        Number(new RegExp(`${keyword}: (\\d+)`).exec(parameterBody)?.[1]);
      const limit = tsNumberConst(entry.store, 'PAGE_LIMIT');
      expect(
        limit,
        `${entry.store} 的 PAGE_LIMIT 应等于契约 ${entry.parameter} 默认值`,
     ).toBe(readFromParameter('default'));
      expect(limit >= readFromParameter('minimum'), `${entry.store} 的 PAGE_LIMIT 低于契约下限`).toBe(true);
      expect(limit <= readFromParameter('maximum'), `${entry.store} 的 PAGE_LIMIT 高于契约上限`).toBe(true);
    }
  });

  /**
   * 审计的两个格式约束在契约上写了两遍：查询参数一次、读侧条目字段一次，两者没有引用关系可以保障同步。
   * 前端只镜像一套常量（查询表单的本地预拦与条目的类型都复用它），所以这里先逐字节对齐契约的两处，
   * 再由 `PATTERN_HOSTS` 对前端做行为对账——两处契约漂移都会在这一组断言里现形。
   */
  it('binds the audit query parameter patterns to the read-side mirror', () => {
    expect(
      parameterConstraint('AuditTraceId', 'pattern'),
      '查询参数 AuditTraceId.pattern 与读侧 AuditEventItem.traceId 已漂移，前端共用的一套常量不再成立',
   ).toBe(contractConstraint('AuditEventItem', 'traceId', 'pattern'));
    expect(
      parameterConstraint('AuditEventType', 'pattern'),
      '查询参数 AuditEventType.pattern 与读侧 AuditEventItem.eventType 已漂移',
   ).toBe(contractConstraint('AuditEventItem', 'eventType', 'pattern'));
  });

  /**
   * 用量看板的 `bucket` 与 `topUsers` 在契约上都可缺省，前端仍显式发送：
   * 「不发送」等于把口径交给服务端默认值，请求不可复现。因此这两个常量必须等于契约默认值，
   * 而 `bucket` 必须恰好落在契约枚举的那一个取值上（契约将来增加 `hour` 时这里先红）。
   */
  it('sends the usage aggregation defaults the contract declares', () => {
    expect(
      tsNumberConst('src/stores/adminUsage.ts', 'TOP_USERS'),
      'adminUsage 的 TOP_USERS 应等于契约 UsageTopUsers 默认值',
   ).toBe(Number(parameterConstraint('UsageTopUsers', 'default')));
    expect(
      contractFieldEnum('UsageSummary', 'bucket'),
      '契约的 bucket 枚举不再是单值，前端发送的常量与 UsageBucket 类型都要重新裁决',
   ).toEqual([tsStringConst('src/stores/adminUsage.ts', 'BUCKET')]);
  });

  /**
   *  的机器化。设计期注释曾假设管理端写入带 `version` 乐观锁与幂等键头，交付的契约两样都没有：
   * 两个管理面 PUT 的请求体各只有契约列出的字段，管理面八个操作也都不引用任何幂等键参数。
   * 「不加任何头」与「按值幂等、并发覆盖为 last-write-wins」这两条界面表述就站在这条断言上；
   * 契约一旦引入幂等键或给管理端加上 429，这里先红，界面文案与 store 字段都要重新裁决。
   */
  it('keeps the admin surface exactly the parameter set the contract publishes', () => {
    const expectedParameters: Record<string, string[]> = {
      getAiControls: [],
      updateAiControls: [],
      getTenantAiControls: [],
      updateTenantAiControls: [],
      listAiQuotas: ['QuotaListLimit', 'KeysetCursor'],
      updateAiQuota: ['UserId'],
      listAuditEvents: [
        'AuditTraceId',
        'AuditUserId',
        'AuditEventType',
        'TimeFrom',
        'TimeTo',
        'AuditQueryLimit',
        'KeysetCursor',
      ],
      getAiUsage: ['TimeFrom', 'TimeTo', 'UsageBucket', 'UsageTopUsers'],
      // 契约 2.8.0 可观测性入口：只读部署配置，无参数、无幂等键。
      getObservability: [],
      // 契约 2.7.0 模型连接管理面：同样按值幂等，不声明幂等键。
      listModelConnections: [],
      listModelConnectionPresets: [],
      upsertModelConnection: [],
      deleteModelConnection: ['ConnectionId'],
      authorizeModelConnection: ['ConnectionId'],
      probeModelConnection: ['ConnectionId'],
      updateModelConnectionActivation: ['ConnectionId'],
      // 契约 2.8.0 长期记忆管理面：读与删都按值幂等，不声明幂等键。
      listMemories: ['MemoryUserId'],
      deleteMemory: ['MemoryKey', 'MemoryUserId'],
    };
    for (const [operationId, names] of Object.entries(expectedParameters)) {
      const refs = operationParameterRefs(operationBody(operationId));
      expect(refs.slice().sort(), `${operationId} 的参数集合与登记不一致：新增参数要先决定镜像位置`).toEqual(
        names.slice().sort(),
     );
      expect(
        refs,
        `${operationId} 引用了幂等键参数，「管理端不加任何头」的裁决需要重来`,
     ).not.toContain('IdempotencyKey');
      expect(refs, `${operationId} 引用了可选幂等键参数，同上`).not.toContain('IdempotencyKeyOptional');
      expect(
        operationBody(operationId).join('\n'),
        `${operationId} 出现 429：管理端 store 要按其余域补齐 retryAfterSeconds 与等待文案`,
     ).not.toContain("'429':");
    }

    expect(
      contractFields(SCHEMAS.get('UpdateAiControlsRequest') ?? []),
      'UpdateAiControlsRequest 的字段集合已变化：界面文案里的「按值幂等」要重新核实',
   ).toEqual(['killSwitchActive']);
    expect(contractFields(SCHEMAS.get('UpdateAiQuotaRequest') ?? [])).toEqual(['dailyQuestionLimit', 'dailyTokenLimit']);
  });

  /**
   *  的机器化，方向与上面那条**相反**：用户管理面的两个写操作与改密在契约里都正式声明了
   * `IdempotencyKey`（Java 侧 `@RequestHeader` 必填），所以这里断言的是「参数集合**含**该参数」。
   * 这一条存在的理由是把两个面隔开：`api/admin.ts` 那条「不加任何头」的裁决一旦被复制到用户面，
   * 三个写请求会直接 400；反过来契约若把该参数删掉，拦截器补的头就成了无契约依据的额外请求头。
   * 同时锁定三个请求体的字段集合，以及 `roles` 在读写两侧的**不对称**：读侧 `minItems: 1`、
   * 写侧无下限——空数组是「schema 合法、服务端 422」的业务策略，界面据这一点把它挡成策略而非格式错误。
   */
  it('keeps the user-management surface exactly the parameter set the contract publishes', () => {
    const expectedParameters: Record<string, string[]> = {
      listUsers: ['UserStatusFilter', 'UserRoleFilter', 'KeysetCursor', 'PageLimit'],
      getUser: ['UserId'],
      replaceUserRoles: ['UserId', 'IdempotencyKey'],
      updateUserStatus: ['UserId', 'IdempotencyKey'],
      changePassword: ['IdempotencyKey'],
    };
    const writes = new Set(['replaceUserRoles', 'updateUserStatus', 'changePassword']);
    for (const [operationId, names] of Object.entries(expectedParameters)) {
      const refs = operationParameterRefs(operationBody(operationId));
      expect(refs.slice().sort(), `${operationId} 的参数集合与登记不一致：新增参数要先决定镜像位置`).toEqual(
        names.slice().sort(),
     );
      if (writes.has(operationId)) {
        expect(refs, `${operationId} 不再声明 IdempotencyKey：拦截器补的那个头就失去契约依据`).toContain('IdempotencyKey');
      }
    }

    expect(
      contractFields(SCHEMAS.get('ChangePasswordRequest') ?? []),
      'ChangePasswordRequest 的字段集合已变化：改密表单的字段与 writeOnly 口径要重新核实',
   ).toEqual(['currentPassword', 'newPassword']);
    expect(contractFields(SCHEMAS.get('UserRolesUpdateRequest') ?? [])).toEqual(['roles']);
    expect(contractFields(SCHEMAS.get('UserStatusUpdateRequest') ?? [])).toEqual(['status']);
    expect(
      contractConstraint('UserSummaryData', 'roles', 'minItems'),
      '读侧 roles 的 minItems 已变化：界面那句「账号必须至少持有 USER」要重新核实',
   ).toBe('1');

    const rolesBlock = (SCHEMAS.get('UserRolesUpdateRequest') ?? []).join('\n');
    expect(/ {10}maxItems: 2/.test(rolesBlock), 'UserRolesUpdateRequest.roles 的 maxItems 不再是 2').toBe(true);
    expect(
      rolesBlock.includes('minItems'),
      '契约已给写侧 roles 加上 minItems：空数组不再是「schema 合法、服务端 422」，界面那层预拦要重新裁决',
   ).toBe(false);

    // 改密的两个界面前提都在契约上：204 无响应体 ⇒ 成功后必须重新登录；429 ⇒ 需要等待倒数。
    const changePasswordBody = operationBody('changePassword').join('\n');
    expect(changePasswordBody, "changePassword 不再返回 204：「成功即登出」的前提变了").toContain("'204':");
    expect(changePasswordBody, "changePassword 不再有 429：账户页的限流倒数与「这算一次失败」文案要撤下").toContain("'429':");
  });

  it('routes every error response through the single mirrored ProblemDetail', () => {
    // 契约 2.8.0 的 components.responses 计数（2.5.0 的 35 个 + MemoryNotFound），全部复用同一个 ProblemDetail。
    expect(RESPONSE_BLOCKS).toHaveLength(36);
    const offContract = RESPONSE_BLOCKS.filter(
      (block) => !block.body.some((line) => line.trim() === "$ref: '#/components/schemas/ProblemDetail'"),
   ).map((block) => block.name);
    expect(offContract, `这些响应没有复用 ProblemDetail：${offContract.join(', ')}`).toEqual([]);
  });

  it('keeps the "null keys are always serialized" premise the mirrors rely on', () => {
    // ALLOWED_STRICTER 第 1 类的前提是后端把 null 也序列化成键；一旦引入排除配置，这个前提就不成立了。
    const backendRoot = join(ROOT, '..', 'backend-java');
    const needles = ['@JsonInclude', 'setSerializationInclusion', 'default-property-inclusion'];
    const offenders = sourceFilesUnder(backendRoot, /src[\\/]main[/].*\.(java|yml|yaml|properties)$/)
      .filter((file) => needles.some((needle) => readFileSync(file, 'utf8').includes(needle)))
      .map((file) => file.slice(backendRoot.length + 1).split(/[\\/]/).join('/'));

    expect(
      offenders,
      `后端引入了排除 null 的 Jackson 装配：${offenders.join(', ')}；前端的「键恒在」镜像需要重新裁决。`,
   ).toEqual([]);
  });

  /**
   * CT-3：从契约文本读某字段是否声明 nullable。原先 49 处 nullable 没有任何机检入口，
   * 前端类型把字段写成可选/必填全靠人工登记；这里补上可复用的判据。
   */
  function contractNullable(schema: string, field: string): boolean {
    const body = SCHEMAS.get(schema);
    if (body === undefined) {
      return false;
    }
    const start = body.findIndex((line) => line === `        ${field}:`);
    if (start < 0) {
      return false;
    }
    for (let index = start + 1; index < body.length; index += 1) {
      if (/^ {8}[A-Za-z]/.test(body[index])) {
        break; // 已跨到下一个字段
      }
      if (body[index].trim() === 'nullable: true') {
        return true;
      }
    }
    return false;
  }

  it('registers every constraint keyword found in the yaml (CT-3)', () => {
    // 登记表由 scripts/check_contract_single_source.py --emit-registry 生成。
    // 这里用一条**独立的**正则反向扫全文，断言「扫到的每一处都进了登记表」——
    // 正是方案 CT-3 指出的「369 处约束只有约 60 条手工登记、且没有反向扫描」缺口。
    const registry = JSON.parse(
      readFileSync(join(ROOT, '..', 'scripts', 'contract_constraints.json'), 'utf8'),
   ) as {
      keywords: string[];
      entries: Array<{ line: number; keyword: string; schema: string; field: string; value: unknown }>;
    };
    const keywordRe = new RegExp(`^\\s+(${registry.keywords.join('|')}):\\s*.*$`);
    const scanned = new Set<string>();
    contractLines.forEach((line, index) => {
      const match = keywordRe.exec(line);
      if (match !== null) {
        scanned.add(`${index + 1}:${match[1]}`);
      }
    });
    expect(scanned.size).toBeGreaterThan(100); // 防「两端同时变空」的恒真

    const registered = new Set(registry.entries.map((entry) => `${entry.line}:${entry.keyword}`));
    const missing = [...scanned].filter((item) => !registered.has(item));
    expect(missing, `这些约束未进登记表：${missing.slice(0, 5).join(', ')}`).toEqual([]);
    // 反向：改了契约却忘重新生成登记表，也要变红。
    const stale = [...registered].filter((item) => !scanned.has(item));
    expect(stale).toEqual([]);
  });

  it('contractNullable resolves every registered nullable field (CT-3)', () => {
    const registry = JSON.parse(
      readFileSync(join(ROOT, '..', 'scripts', 'contract_constraints.json'), 'utf8'),
   ) as {
      entries: Array<{ keyword: string; schema: string; field: string; value: unknown }>;
    };
    const nullableFields = registry.entries.filter(
      (entry) => entry.keyword === 'nullable' && entry.value === true && entry.schema && entry.field,
   );
    expect(nullableFields.length).toBeGreaterThan(0);
    for (const entry of nullableFields) {
      expect(contractNullable(entry.schema, entry.field), `${entry.schema}.${entry.field}`).toBe(true);
    }
    // 反例：明确的必填字段不能被误判为可空。
    expect(contractNullable('AskRequest', 'question')).toBe(false);
  });
});
