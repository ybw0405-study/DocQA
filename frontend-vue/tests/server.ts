import { setupServer } from 'msw/node';
import {
  adminHandlers,
  authHandlers,
  conversationHandlers,
  documentHandlers,
  notificationHandlers,
  reminderHandlers,
  scheduleHandlers,
  usersHandlers,
} from './fixtures';

export const server = setupServer(
  ...authHandlers,
  ...documentHandlers,
  ...conversationHandlers,
  ...reminderHandlers,
  ...scheduleHandlers,
  // 通知面板随提醒页挂载，两个请求都得出得了服务端结果，否则会退化成「无法连接服务」。
  ...notificationHandlers,
  // 管理面八个操作（S9 六个平台级 + 2.0.0 两个租户级）：api、store 与管理视图共用同一份按契约校验入参形态的桩。
  ...adminHandlers,
  //  用户管理面三个操作（改密走 authHandlers 里的 `/auth/password`）：同一套桩纪律。
  ...usersHandlers,
);
