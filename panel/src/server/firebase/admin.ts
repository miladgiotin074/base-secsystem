import { cert, getApps, initializeApp, type App } from "firebase-admin/app";
import { getFirestore, type Transaction } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

const COLLECTION = "port_endpoints";

export type PortEndpointDoc = {
  wsUrl: string;
  rev: number;
  updatedAt: string;
};

function initApp(): App {
  const existing = getApps()[0];
  if (existing) return existing;

  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim();
  if (!raw) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not set");
  }
  const serviceAccount = JSON.parse(raw) as {
    project_id?: string;
    client_email?: string;
    private_key?: string;
  };
  if (!serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error("Invalid FIREBASE_SERVICE_ACCOUNT_JSON");
  }
  return initializeApp({
    credential: cert({
      projectId: serviceAccount.project_id || process.env.FIREBASE_PROJECT_ID,
      clientEmail: serviceAccount.client_email,
      privateKey: serviceAccount.private_key.replace(/\\n/g, "\n"),
    }),
  });
}

export function firebaseReady(): boolean {
  return Boolean(process.env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim());
}

export async function getPortEndpoint(port: string): Promise<PortEndpointDoc | null> {
  const app = initApp();
  const snap = await getFirestore(app).collection(COLLECTION).doc(port).get();
  if (!snap.exists) return null;
  const data = snap.data() || {};
  const wsUrl = typeof data.wsUrl === "string" ? data.wsUrl.trim() : "";
  const rev = typeof data.rev === "number" ? data.rev : Number(data.rev) || 0;
  if (!wsUrl) return null;
  return {
    wsUrl,
    rev,
    updatedAt: typeof data.updatedAt === "string" ? data.updatedAt : "",
  };
}

export async function setPortEndpoint(port: string, wsUrl: string): Promise<PortEndpointDoc> {
  const app = initApp();
  const ref = getFirestore(app).collection(COLLECTION).doc(port);
  const next = await getFirestore(app).runTransaction(async (tx: Transaction) => {
    const snap = await tx.get(ref);
    const prevRev = snap.exists ? Number(snap.data()?.rev) || 0 : 0;
    const doc: PortEndpointDoc = {
      wsUrl,
      rev: prevRev + 1,
      updatedAt: new Date().toISOString(),
    };
    tx.set(ref, doc, { merge: true });
    return doc;
  });

  const topic = `port_${port}`;
  try {
    await getMessaging(app).send({
      topic,
      data: {
        type: "server_url",
        port,
        wsUrl: next.wsUrl,
        rev: String(next.rev),
      },
      android: {
        priority: "high",
      },
    });
  } catch {
    // Firestore write is source of truth; FCM is best-effort.
  }
  return next;
}

export function isValidWsUrl(url: string): boolean {
  return url.startsWith("ws://") || url.startsWith("wss://");
}
