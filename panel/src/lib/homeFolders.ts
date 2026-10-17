export type HomeFolder = {
  id: string;
  title: string;
  kind: "all" | "online" | "offline" | "muted" | "custom";
};

const STORAGE_KEY = "secsystem.homeFolders";

export const builtinFolders: HomeFolder[] = [
  { id: "all", title: "همه", kind: "all" },
  { id: "online", title: "آنلاین", kind: "online" },
  { id: "offline", title: "آفلاین", kind: "offline" },
];

export const mutedFolder: HomeFolder = { id: "muted", title: "بیصدا", kind: "muted" };

export function homeFoldersWithMuted(custom: HomeFolder[], hasMuted: boolean): HomeFolder[] {
  return hasMuted ? [...builtinFolders, mutedFolder, ...custom] : [...builtinFolders, ...custom];
}

type Stored = {
  folders: { id: string; title: string }[];
  members: Record<string, string[]>;
  /** Pins per folder id. */
  pins: Record<string, string[]>;
};

function emptyStored(): Stored {
  return { folders: [], members: {}, pins: {} };
}

function normalizePins(raw: unknown): Record<string, string[]> {
  if (Array.isArray(raw)) {
    const legacy = raw.filter((id): id is string => typeof id === "string" && id);
    return legacy.length ? { all: legacy } : {};
  }
  if (!raw || typeof raw !== "object") return {};
  const out: Record<string, string[]> = {};
  for (const [folderId, ids] of Object.entries(raw as Record<string, unknown>)) {
    if (!folderId || !Array.isArray(ids)) continue;
    const list = ids.filter((id): id is string => typeof id === "string" && id);
    if (list.length) out[folderId] = list;
  }
  return out;
}

export function readFolders(): Stored {
  if (typeof window === "undefined") {
    return emptyStored();
  }
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return emptyStored();
    const parsed = JSON.parse(raw) as Stored;
    return {
      folders: Array.isArray(parsed.folders) ? parsed.folders.filter((folder) => folder?.id && folder?.title) : [],
      members: parsed.members && typeof parsed.members === "object" ? parsed.members : {},
      pins: normalizePins(parsed.pins),
    };
  } catch {
    return emptyStored();
  }
}

export function writeFolders(stored: Stored) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
}

export function customFolders(stored: Stored): HomeFolder[] {
  return stored.folders.map((folder) => ({ id: folder.id, title: folder.title, kind: "custom" as const }));
}

export function pinsFor(pins: Record<string, string[]>, folderId: string): string[] {
  return pins[folderId] || [];
}
