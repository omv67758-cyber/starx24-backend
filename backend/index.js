/**
 * STARX24 payment backend for Render.
 *
 * Secrets stay in Render environment variables:
 * - ZAPUPI_KEY
 * - FIREBASE_SERVICE_ACCOUNT (raw JSON or base64 JSON)
 * - FIREBASE_DATABASE_URL
 *
 * The Android app never receives the ZapUPI key. It sends a Firebase ID token
 * to this service, which creates and verifies orders with the Firebase Admin SDK.
 */

// Some container hosts (Railway included) advertise IPv6 but don't actually
// route it, so Node's built-in fetch() tries the IPv6 address first, times
// out/refuses, and reports it as a generic "fetch failed" with the real
// cause hidden. Preferring IPv4 avoids that dead-end entirely.
try {
  require("node:dns").setDefaultResultOrder("ipv4first");
} catch (_e) {
  // Older Node without this API — harmless to skip.
}

// dns.setDefaultResultOrder above only reorders dns.lookup() results — it
// does NOT stop Node's fetch()/undici from still attempting a resolved IPv6
// address if the host has one (confirmed in production logs: ENETUNREACH on
// ZapUPI's IPv6 address even with ipv4first set). Forcing every fetch() in
// this process onto IPv4-only sockets removes that dead-end attempt
// entirely, instead of just deprioritizing it.
try {
  const { Agent, setGlobalDispatcher } = require("undici");
  setGlobalDispatcher(new Agent({ connect: { family: 4 } }));
} catch (_e) {
  // If undici isn't resolvable for some reason, fall through — the
  // ipv4first dns setting above still applies as a partial mitigation.
}

const express = require("express");
const admin = require("firebase-admin");
const rateLimit = require("express-rate-limit");
const { randomBytes } = require("node:crypto");

function readServiceAccount() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT?.trim();
  const base64 = process.env.FIREBASE_SERVICE_ACCOUNT_BASE64?.trim();
  const encoded = base64
    ? Buffer.from(base64, "base64").toString("utf8")
    : raw;

  if (!encoded) {
    throw new Error("Missing FIREBASE_SERVICE_ACCOUNT");
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(encoded);
  } catch (error) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT must be valid JSON or base64-encoded JSON");
  }

  if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT is missing project_id, client_email, or private_key");
  }

  return {
    ...serviceAccount,
    private_key: serviceAccount.private_key.replace(/\\n/g, "\n"),
  };
}

const serviceAccount = readServiceAccount();
const databaseURL = process.env.FIREBASE_DATABASE_URL?.trim()
  || `https://${serviceAccount.project_id}-default-rtdb.firebaseio.com`;
const zapupiKey = process.env.ZAPUPI_KEY?.trim();
const zapupiMode = (process.env.ZAPUPI_MODE || "TEST").trim().toUpperCase();
const publicBaseUrl = (process.env.PUBLIC_BASE_URL?.trim()
  || process.env.RENDER_EXTERNAL_URL?.trim())?.replace(/\/+$/, "");
// Same bootstrap master identity the Firebase rules and the Android app's
// admin_keys/admin_sessions RBAC already trust. Keeping this in sync with
// database.rules.json means the owner's Gmail account works as MASTER_ADMIN
// here too, without introducing a second (custom-claims) admin system.
const MASTER_EMAIL = (process.env.MASTER_EMAIL || "fflueclark@gmail.com").trim().toLowerCase();

if (!zapupiKey) throw new Error("Missing ZAPUPI_KEY");
if (!publicBaseUrl) throw new Error("Missing PUBLIC_BASE_URL");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL,
});

const db = admin.database();
const app = express();
const port = Number(process.env.PORT || 3000);
const CREATE_ORDER_URL = "https://pay.zapupi.com/api/create-order";
const ORDER_STATUS_URL = "https://pay.zapupi.com/api/order-status";

app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));

app.get("/", (_req, res) => {
  res.status(200).json({ service: "STARX24 payment backend", status: "ok" });
});

app.get("/health", (_req, res) => {
  res.status(200).json({ status: "ok" });
});

function getBearerToken(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

// Applied per-uid (falls back to IP if the token is missing/invalid — the
// route handler still rejects those) so one abusive account can't hammer
// money-moving endpoints. 20 requests/minute is generous for a real user
// tapping buttons and tight enough to stop scripted abuse.
const moneyLimiter = rateLimit({
  windowMs: 60_000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => getBearerToken(req) || req.ip,
  message: { error: "Too many requests. Please wait a moment and try again." },
});

/**
 * Resolves the caller's admin role using the SAME admin_keys/admin_sessions
 * lookup the Android app and database.rules.json already use, instead of a
 * separate custom-claims system. Reading with the Admin SDK bypasses rules,
 * so this always sees the live value.
 */
async function resolveAdminRole(uid, email) {
  if (email && email.trim().toLowerCase() === MASTER_EMAIL) {
    return { role: "MASTER_ADMIN", active: true };
  }
  const sessionKey = (await db.ref(`admin_sessions/${uid}/key`).once("value")).val();
  if (!sessionKey) return { role: null, active: false };
  const record = (await db.ref(`admin_keys/${sessionKey}`).once("value")).val();
  if (!record || record.active !== true) return { role: null, active: false };
  return { role: String(record.role || ""), active: true };
}

function isMasterOrPayment(role) {
  return role === "MASTER_ADMIN" || role === "SUPER_ADMIN" || role === "PAYMENT_ADMIN";
}

/** Express middleware: verifies the Firebase session AND that the caller is
 *  an active admin whose role passes `allow(role)`. Sets req.decoded / req.adminRole. */
function requireAdmin(allow) {
  return async (req, res, next) => {
    const idToken = getBearerToken(req);
    if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });
    let decoded;
    try {
      decoded = await admin.auth().verifyIdToken(idToken);
    } catch (_error) {
      return res.status(401).json({ error: "Invalid Firebase authorization token" });
    }
    const { role, active } = await resolveAdminRole(decoded.uid, decoded.email);
    if (!active || !allow(role)) {
      return res.status(403).json({ error: "You do not have permission to do this" });
    }
    req.decoded = decoded;
    req.adminRole = role;
    next();
  };
}

/** Every admin money/result action gets one line here — who, what, on whom. */
async function logActivity(actorUid, action, details) {
  await db.ref("activity_logs").push({
    actorUid,
    action,
    details: details || "",
    at: admin.database.ServerValue.TIMESTAMP,
  }).catch((error) => console.error("activity log write failed", error.message));
}

function validOrderId(orderId) {
  return typeof orderId === "string" && /^STARX[0-9]+_[a-z0-9]{6}$/.test(orderId);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * fetch() wrapped with an explicit timeout (Node's fetch has no default one,
 * so a stalled connection would otherwise hang until the client gives up)
 * and a few retries on NETWORK-level failures only — DNS hiccups, connection
 * resets, the odd Render/ZapUPI cold-start blip. A response that actually
 * comes back (even an error one, e.g. "invalid key") is returned as-is and
 * is never retried, since retrying a real rejection wouldn't change it.
 */
async function fetchWithRetry(url, options, { attempts = 4, timeoutMs = 10_000 } = {}) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(url, { ...options, signal: controller.signal });
      clearTimeout(timer);
      return response;
    } catch (error) {
      clearTimeout(timer);
      lastError = error;
      if (attempt < attempts) {
        // 500ms, 1500ms, 4500ms — gives a short-lived network blip real
        // room to clear without making the user wait too long overall.
        await sleep(500 * Math.pow(3, attempt - 1));
      }
    }
  }
  throw lastError;
}

/** Firebase RTDB writes can also hit the same transient network wobble as
 *  the ZapUPI call — retry a couple of times before giving up on saving
 *  the order, instead of failing the whole request on the first blip. */
async function withRetry(fn, attempts = 3) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(300 * Math.pow(3, attempt - 1));
    }
  }
  throw lastError;
}

async function createZapupiOrder(orderId, amount, mobile) {
  const response = await fetchWithRetry(CREATE_ORDER_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      zap_key: zapupiKey,
      order_id: orderId,
      amount: String(amount),
      customer_mobile: mobile || undefined,
      remark: `STARX24 wallet | ${orderId}`,
      webhook_url: `${publicBaseUrl}/zapupiWebhook`,
    }),
  });

  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch (_error) {
    data = {};
  }

  return { response, data };
}

function paymentProviderError(data, statusCode) {
  const providerMessage = String(data?.message || data?.error || "").trim();
  if (/invalid zap key/i.test(providerMessage)) {
    return "Payment gateway key is invalid or expired. Update ZAPUPI_KEY in Render.";
  }
  if (/key/i.test(providerMessage) && /(missing|expired|inactive|disabled)/i.test(providerMessage)) {
    return "Payment gateway key is not active. Check ZAPUPI_KEY in Render.";
  }
  if (/insufficient.*(topup|balance)/i.test(providerMessage) || /topup.*(balance|insufficient)/i.test(providerMessage)) {
    // Not a network/code problem at all: ZapUPI's merchant wallet itself is
    // out of prepaid balance, so no retry will ever fix this — it needs a
    // top-up on the ZapUPI dashboard before orders can be created again.
    return "Payment gateway balance is exhausted. Please top up the ZapUPI merchant account.";
  }
  if (providerMessage) {
    console.error("ZapUPI provider message", { statusCode, providerMessage });
  }
  return "Payment provider rejected the order. Please try again.";
}

app.post("/createOrder", moneyLimiter, async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });

  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const amount = Number(req.body?.amount);
  // Add Money limits: minimum ₹10, maximum ₹10000 per payment.
  if (!Number.isInteger(amount) || amount < 10 || amount > 10000) {
    return res.status(400).json({ error: "Amount must be between 10 and 10000" });
  }

  const mobile = String(req.body?.mobile || "").trim();
  if (mobile && !/^[0-9+() -]{7,20}$/.test(mobile)) {
    return res.status(400).json({ error: "Invalid mobile number" });
  }

  const orderId = `STARX${Date.now()}_${randomBytes(3).toString("hex")}`;
  const orderRef = db.ref(`orders/${orderId}`);

  try {
    await withRetry(() => orderRef.set({
      uid: decoded.uid,
      amount,
      status: "pending",
      mode: zapupiMode,
      createdAt: admin.database.ServerValue.TIMESTAMP,
    }));
  } catch (error) {
    // Every retry of the Firebase write itself failed — this is the one
    // case that truly deserves "could not save the order", so log the real
    // cause (DNS/connection/TLS details Node hides in error.cause) for
    // diagnosis and tell the user plainly.
    console.error("createOrder: order write failed after retries", error.message, error.cause || "");
    return res.status(500).json({ error: "Payment service could not save the order. Please try again." });
  }

  try {
    const { response, data } = await createZapupiOrder(orderId, amount, mobile);
    const providerStatus = String(data?.status || "").trim().toLowerCase();
    if (!response.ok || providerStatus !== "success" || !data.payment_url) {
      await orderRef.update({ status: "create_failed" }).catch((updateError) => {
        console.error("Could not mark failed order", updateError.message);
      });
      console.error("ZapUPI create-order rejected", {
        httpStatus: response.status,
        providerStatus,
        providerMessage: data.message || data.error || "",
      });
      return res.status(502).json({
        error: paymentProviderError(data, response.status),
      });
    }

    return res.status(200).json({
      order_id: orderId,
      payment_url: data.payment_url,
    });
  } catch (error) {
    // The order itself is already saved as "pending" at this point, so this
    // catch only covers the ZapUPI call still failing after all of
    // fetchWithRetry's attempts (real network outage, not a cold-start blip).
    // Node's fetch() wraps low-level network errors (DNS failure, refused
    // connection, TLS problems, IPv6 routing issues on some hosts) inside a
    // generic "fetch failed" message and hides the real reason in
    // error.cause. Logging the cause is the only way to actually see why.
    console.error("createOrder: ZapUPI call failed after retries", error.message, error.cause || "");
    await orderRef.update({ status: "create_failed" }).catch(() => {});
    return res.status(502).json({
      error: "Could not reach the payment provider. Please check your internet and try again.",
    });
  }
});

// ZapUPI dashboard test pings can be GET requests.
app.get("/zapupiWebhook", (_req, res) => res.status(200).send("ok"));

async function getVerifiedOrderStatus(orderId) {
  let response;
  try {
    response = await fetchWithRetry(ORDER_STATUS_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ zap_key: zapupiKey, order_id: orderId }),
    });
  } catch (error) {
    // Network kept failing even after retries — treat as "unknown for now"
    // rather than throwing, so the caller reports "pending" and the app's
    // next onResume (or the webhook's own retry) tries again later instead
    // of the request blowing up.
    console.error("getVerifiedOrderStatus failed after retries", error.message, error.cause || "");
    return {};
  }
  const text = await response.text();
  try {
    return response.ok ? JSON.parse(text) : {};
  } catch (_error) {
    return {};
  }
}

async function creditWalletOnce(order) {
  const walletRef = db.ref(`users/${order.uid}/wallet`);
  const result = await walletRef.transaction((current) => {
    const wallet = current && typeof current === "object" ? { ...current } : {};
    const creditedOrders = wallet.creditedOrders && typeof wallet.creditedOrders === "object"
      ? { ...wallet.creditedOrders }
      : {};

    if (creditedOrders[order.orderId]) return;

    const currentBalance = Number(wallet.balance || 0);
    wallet.balance = currentBalance + Number(order.amount);
    creditedOrders[order.orderId] = {
      amount: Number(order.amount),
      creditedAt: admin.database.ServerValue.TIMESTAMP,
    };
    wallet.creditedOrders = creditedOrders;
    return wallet;
  });

  if (result.committed) {
    // Record a DEPOSIT entry so the app's Wallet history list (which reads
    // users/<uid>/wallet/transactions) shows this top-up, matching the
    // Transaction model's fields (type/title/amount/timestamp/status).
    await db.ref(`users/${order.uid}/wallet/transactions`).push({
      type: "DEPOSIT",
      title: "Wallet Top-up",
      amount: Number(order.amount),
      timestamp: admin.database.ServerValue.TIMESTAMP,
      status: "SUCCESS",
    });
    // FIX (notifications): the user should immediately see + hear about a
    // successful top-up. Previously only the wallet balance changed silently.
    try {
      await createUserNotifications([order.uid], "Wallet credited",
        `Your wallet was credited with ₹${Number(order.amount)}.`, "WALLET_CREDIT",
        { orderId: order.orderId });
      const token = (await db.ref(`users/${order.uid}/fcmToken`).once("value")).val();
      if (token) {
        await sendPush([token], "Wallet credited 💰",
          `₹${Number(order.amount)} added to your STARX24 wallet. Play tournaments now!`,
          { type: "WALLET_CREDIT", orderId: order.orderId });
      }
    } catch (pushError) {
      console.error("wallet credit push error", pushError.message);
    }
  }

  return result.committed;
}

/**
 * Shared settlement logic used by BOTH the ZapUPI webhook and the app's
 * manual /verifyOrder call. Re-checks the real status with ZapUPI itself
 * (never trusts the caller), credits the wallet at most once, and returns
 * the final order status. Safe to call multiple times for the same order.
 */
async function settleOrder(orderId, storedOrder) {
  if (storedOrder.status === "credited") return "credited";
  if (storedOrder.status === "failed") return "failed";

  const statusData = await getVerifiedOrderStatus(orderId);
  // ZapUPI returns an outer API status of "success" when the status lookup
  // itself succeeded. That is NOT the payment result. The actual transaction
  // result is statusData.data.status (Pending | Success | Failed).
  const verifiedStatus = String(statusData?.data?.status || "").trim().toLowerCase();

  if (verifiedStatus === "success") {
    const order = { ...storedOrder, orderId };
    await db.ref(`orders/${orderId}/status`).transaction((current) => {
      if (current === "pending" || current === "processing") return "processing";
      return;
    });

    await creditWalletOnce(order);
    await db.ref(`orders/${orderId}`).update({
      status: "credited",
      creditedAt: admin.database.ServerValue.TIMESTAMP,
    });
    return "credited";
  }

  if (verifiedStatus === "failed") {
    await db.ref(`orders/${orderId}/status`).set("failed");
    return "failed";
  }

  return "pending";
}

async function sendPush(tokens, title, body, data = {}) {
  const usable = Array.from(new Set((tokens || []).filter(Boolean)));
  for (let offset = 0; offset < usable.length; offset += 500) {
    const chunk = usable.slice(offset, offset + 500);
    if (!chunk.length) continue;
    await admin.messaging().sendEachForMulticast({
      tokens: chunk,
      notification: { title, body },
      data: Object.fromEntries(Object.entries(data).map(([key, value]) => [key, String(value)])),
    });
  }
}

async function getAllUserTokens() {
  const snapshot = await db.ref("users").once("value");
  const tokens = [];
  snapshot.forEach((user) => {
    const token = user.child("fcmToken").val();
    if (typeof token === "string" && token.trim()) tokens.push(token.trim());
  });
  return tokens;
}

async function getJoinedUids(matchSnapshot) {
  const node = matchSnapshot.child("joinedUsers").exists()
    ? matchSnapshot.child("joinedUsers")
    : matchSnapshot.child("participants");
  const uids = [];
  node.forEach((player) => {
    if (player.key) uids.push(player.key);
  });
  // FREE matches joined straight from the app (FirebaseDirectJoinClient) only
  // write tournaments/{id}/participants — the matches/ mirror is admin-only
  // under the security rules, so a free-join player would otherwise never be
  // counted here and would miss the room-release notification entirely.
  if (!uids.length) {
    const tournamentId = matchSnapshot.key;
    if (tournamentId) {
      const tournamentParticipants = await db.ref(`tournaments/${tournamentId}/participants`).once("value");
      tournamentParticipants.forEach((player) => {
        if (player.key) uids.push(player.key);
      });
    }
  }
  return uids;
}

// Writes into the SAME shared /notifications inbox the Android app's bell
// (NotificationsActivity) reads — it filters that node by a "message" field
// and a userId child, matching a row to a user. This used to write to
// users/{uid}/notifications instead, a path nothing in the app ever reads,
// so wallet-credit, room-release, broadcast and withdrawal paid/rejected
// notices were all silently invisible in the bell (and also never carried a
// device push from that inbox, since the push above is the only thing that
// reached the phone).
async function createUserNotifications(uids, title, description, type, data = {}) {
  const updates = {};
  for (const uid of uids) {
    const notificationId = db.ref("notifications").push().key;
    if (!notificationId) continue;
    updates[`notifications/${notificationId}`] = {
      userId: uid,
      title,
      message: description,
      type,
      createdAt: admin.database.ServerValue.TIMESTAMP,
      read: false,
      ...data,
    };
  }
  if (Object.keys(updates).length) await db.ref().update(updates);
}

let roomWorkerRunning = false;
let broadcastWorkerRunning = false;
let coinWorkerRunning = false;

/**
 * Room releases are processed on the server so a closed Android app still
 * receives the notification at releaseAt. The transaction makes the worker
 * safe to run on more than one Render instance.
 */
async function processRoomReleases() {
  if (roomWorkerRunning) return;
  roomWorkerRunning = true;
  try {
    const snapshot = await db.ref("matches").once("value");
    const now = Date.now();
    const jobs = [];
    snapshot.forEach((matchSnapshot) => {
      const release = matchSnapshot.child("roomRelease");
      const releaseAt = Number(release.child("releaseAt").val() || 0);
      if (release.exists() && releaseAt > 0 && releaseAt <= now
          && release.child("released").val() !== true) {
        jobs.push({ id: matchSnapshot.key, snapshot: matchSnapshot, release });
      }
    });

    for (const job of jobs) {
      const claimed = await db.ref(`matches/${job.id}/roomRelease/released`)
        .transaction((current) => current === true ? undefined : true);
      if (!claimed.committed) continue;

      const roomId = String(job.release.child("roomId").val() || "");
      const roomPassword = String(job.release.child("roomPassword").val() || "");
      await db.ref().update({
        [`matches/${job.id}/roomId`]: roomId,
        [`matches/${job.id}/roomPassword`]: roomPassword,
        [`matches/${job.id}/roomReleased`]: true,
        [`matches/${job.id}/status`]: "ROOM_RELEASED",
        [`tournaments/${job.id}/roomId`]: roomId,
        [`tournaments/${job.id}/roomPassword`]: roomPassword,
        [`tournaments/${job.id}/roomReleased`]: true,
      });

      const uids = await getJoinedUids(job.snapshot);
      await createUserNotifications(uids, "Room details are live",
        "Your match Room ID and password are now available.", "ROOM_RELEASED",
        { matchId: job.id, roomId, roomPassword });
      const tokens = [];
      for (const uid of uids) {
        const token = (await db.ref(`users/${uid}/fcmToken`).once("value")).val();
        if (token) tokens.push(token);
      }
      await sendPush(tokens, "Room details are live",
        "Your match Room ID and password are now available.", { type: "ROOM_RELEASED", matchId: job.id });
      await db.ref(`matches/${job.id}/roomRelease/pushSent`).set(true);
    }
  } catch (error) {
    console.error("room release worker error", error.message);
  } finally {
    roomWorkerRunning = false;
  }
}

async function processBroadcasts() {
  if (broadcastWorkerRunning) return;
  broadcastWorkerRunning = true;
  try {
    // FIX (notifications): the Admin app writes announcements to
    // /announcements (AdminActivity -> FirebaseRepository.publishAnnouncement)
    // while this worker previously ONLY read /broadcast_notifications
    // (written by RoleAdminActivity). That is why most admin "broadcasts"
    // never reached anyone's phone. Both sources are now processed here.
    const sources = [
      { path: "broadcast_notifications", titleField: "title", bodyField: "description" },
      { path: "announcements", titleField: "title", bodyField: "message" },
    ];
    for (const source of sources) {
      const snapshot = await db.ref(source.path).once("value");
      const jobs = [];
      snapshot.forEach((item) => {
        if (item.child("pushSent").val() !== true) jobs.push(item);
      });
      for (const item of jobs) {
        const claimed = await db.ref(`${source.path}/${item.key}/pushSent`)
          .transaction((current) => current === true ? undefined : true);
        if (!claimed.committed) continue;

        const title = String(item.child(source.titleField).val() || "STARX24");
        const description = String(item.child(source.bodyField).val() || "");
        // Scheduled announcements should not fire before their time.
        const scheduledAt = Number(item.child("scheduledAt").val() || 0);
        if (source.path === "announcements" && scheduledAt > Date.now()) {
          // Undo the claim so a future tick can pick it up at the right time.
          await db.ref(`${source.path}/${item.key}/pushSent`).set(false);
          continue;
        }

        // TOURNAMENT/TEAM targeted announcements only go to relevant users.
        const targetType = String(item.child("targetType").val() || "ALL");
        let uids = [];
        if (targetType === "ALL" || source.path === "broadcast_notifications") {
          const users = await db.ref("users").once("value");
          users.forEach((user) => { if (user.key) uids.push(user.key); });
        } else if (targetType === "TOURNAMENT") {
          const matchId = String(item.child("targetId").val() || "");
          if (matchId) {
            const matchSnap = await db.ref(`tournaments/${matchId}`).once("value");
            uids = await getJoinedUids(matchSnap);
          }
        } else if (targetType === "TEAM") {
          // Fall back to all users; team member lists are not stored centrally.
          const users = await db.ref("users").once("value");
          users.forEach((user) => { if (user.key) uids.push(user.key); });
        }
        if (!uids.length) {
          await db.ref(`${source.path}/${item.key}/pushSent`).set(true);
          continue;
        }

        const tokens = await Promise.all(uids.map(async (uid) => {
          const token = (await db.ref(`users/${uid}/fcmToken`).once("value")).val();
          return typeof token === "string" && token.trim() ? token : null;
        }));
        await createUserNotifications(uids, title, description, "BROADCAST",
          { announcementId: item.key });
        await sendPush(tokens.filter(Boolean), title, description,
          { type: "BROADCAST", announcementId: item.key });
      }
    }
  } catch (error) {
    console.error("broadcast worker error", error.message);
  } finally {
    broadcastWorkerRunning = false;
  }
}

async function processCoinApprovals() {
  if (coinWorkerRunning) return;
  coinWorkerRunning = true;
  try {
    const snapshot = await db.ref("coin_requests").once("value");
    const jobs = [];
    snapshot.forEach((item) => {
      if (item.child("status").val() === "APPROVED" && item.child("pushSent").val() !== true) {
        jobs.push(item);
      }
    });
    for (const item of jobs) {
      const claimed = await db.ref(`coin_requests/${item.key}/pushSent`)
        .transaction((current) => current === true ? undefined : true);
      if (!claimed.committed) continue;
      const uid = String(item.child("uid").val() || "");
      const amount = Number(item.child("amount").val() || 0);
      const token = uid ? await db.ref(`users/${uid}/fcmToken`).once("value") : null;
      if (token?.val()) {
        await sendPush([token.val()], "Coins approved",
          `Your ${amount} coins were approved.`, { type: "COINS_APPROVED", requestId: item.key });
      }
    }
  } catch (error) {
    console.error("coin approval worker error", error.message);
  } finally {
    coinWorkerRunning = false;
  }
}

// Paid tournament registration. The client never writes wallet balances directly:
// this endpoint verifies the Firebase session, checks the server-side entry fee,
// atomically reserves the selected slot and debits wallet.balance exactly once.
app.post("/joinMatch", moneyLimiter, async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });
  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const tournamentId = String(req.body?.tournamentId || "").trim();
  // A26 — the client used to fetch a snapshot of free slots, show them in a
  // spinner, and send back whichever one the player picked. If someone else
  // grabbed that same slot in the seconds between opening the dialog and
  // tapping JOIN, the join failed with "That slot is already taken" even
  // though other slots were free — a race baked into a manual pick from a
  // stale list. The client no longer sends a slot number at all: the server
  // now auto-assigns the next open slot atomically and hands the player that
  // slot number back in the response, so the slot is decided (and shown)
  // AFTER a successful join, never guessed at beforehand.
  const rawSlotNumber = req.body?.slotNumber;
  const requestedSlot = Number(rawSlotNumber);
  const hasRequestedSlot = rawSlotNumber !== undefined && rawSlotNumber !== null && rawSlotNumber !== ""
    && Number.isInteger(requestedSlot) && requestedSlot > 0;

  // Multi-slot join: a player can now grab more than one slot in the same
  // request (e.g. to play with a friend's account, or just to hedge kills
  // across two entries). "slotNumbers" is an optional array of the specific
  // slots the player tapped; if it's missing we fall back to the single
  // "slotNumber"/auto-assign behaviour below with a quantity of 1. Either
  // way the entry fee charged is per-slot × how many slots are claimed —
  // never a flat fee — so a 2-slot join always costs exactly double.
  const MAX_SLOTS_PER_JOIN = 4;
  let requestedSlots = [];
  if (Array.isArray(req.body?.slotNumbers)) {
    const seen = new Set();
    for (const raw of req.body.slotNumbers) {
      const n = Number(raw);
      if (Number.isInteger(n) && n > 0 && !seen.has(n)) { seen.add(n); requestedSlots.push(n); }
    }
  } else if (hasRequestedSlot) {
    requestedSlots = [requestedSlot];
  }
  const requestedQuantity = Number(req.body?.quantity);
  // quantity-only requests (no specific slots picked) ask the server to
  // auto-assign that many free slots, same as the existing single-slot
  // auto-assign path just repeated.
  const quantity = requestedSlots.length > 0
    ? requestedSlots.length
    : (Number.isInteger(requestedQuantity) && requestedQuantity > 0 ? requestedQuantity : 1);

  const gameUid = String(req.body?.gameUid || "").trim();
  const gameName = String(req.body?.gameName || "").trim();

  // Per-slot player details: a Duo/Squad team (or any multi-slot join) needs
  // a Game UID + Game Name for EACH slot, not one shared pair — the players
  // sitting in slot 2 and slot 7 are different people. "players" is an
  // optional array of {slotNumber, gameUid, gameName}, one entry per
  // requested slot. When it's present it must fully cover every requested
  // slot; the flat gameUid/gameName above stays as the single-slot fallback
  // so a plain 1-slot join keeps working unchanged.
  const rawPlayers = Array.isArray(req.body?.players) ? req.body.players : [];
  const playersBySlot = new Map();
  for (const entry of rawPlayers) {
    const slot = Number(entry?.slotNumber);
    const uid = String(entry?.gameUid || "").trim();
    const name = String(entry?.gameName || "").trim();
    if (Number.isInteger(slot) && slot > 0 && uid && name) playersBySlot.set(slot, { gameUid: uid, gameName: name });
  }
  const usingPerSlotPlayers = playersBySlot.size > 0;
  if (usingPerSlotPlayers && requestedSlots.length === 0) {
    return res.status(400).json({ error: "Select specific slots when providing per-player details" });
  }
  if (usingPerSlotPlayers && !requestedSlots.every((slot) => playersBySlot.has(slot))) {
    return res.status(400).json({ error: "Enter the Game UID and Game Name for every selected slot" });
  }

  if (!tournamentId || requestedSlots.some((n) => n > 48) || (!usingPerSlotPlayers && (!gameUid || !gameName))) {
    return res.status(400).json({ error: "Complete match and game details are required" });
  }
  if (quantity > MAX_SLOTS_PER_JOIN) {
    return res.status(400).json({ error: `You can join with at most ${MAX_SLOTS_PER_JOIN} slots at a time` });
  }

  const participantRef = db.ref(`tournaments/${tournamentId}/participants/${decoded.uid}`);
  const walletRef = db.ref(`users/${decoded.uid}/wallet`);
  let debitedAmount = 0;
  let reserved = false;
  const claimedSlotRefs = [];
  try {
    const tournamentSnapshot = await db.ref(`tournaments/${tournamentId}`).once("value");
    if (!tournamentSnapshot.exists()) return res.status(404).json({ error: "Match not found" });
    const tournament = tournamentSnapshot.val() || {};
    if (String(tournament.status || "").toUpperCase() === "COMPLETED" || String(tournament.registrationStatus || "OPEN").toUpperCase() !== "OPEN" || tournament.active === false) {
      return res.status(409).json({ error: "Registration is closed for this match" });
    }
    const totalSlots = Math.max(1, Number(tournament.totalSlots || 48));
    if (requestedSlots.some((n) => n > totalSlots)) {
      return res.status(409).json({ error: "That slot is not available for this match" });
    }
    const remainingSlots = totalSlots - Math.max(0, Number(tournament.joinedSlots || 0));
    if (quantity > Math.max(0, remainingSlots)) {
      return res.status(409).json({ error: remainingSlots <= 0 ? "This match is full" : `Only ${remainingSlots} slot(s) left` });
    }
    // Duo/Squad matches reserve their slots as a fixed-size team, not a
    // pick-your-own quantity: a Duo join must claim exactly 2 slots (one
    // per teammate), a Squad join exactly 4, etc. This is enforced here
    // (not just in the client's dialog) so a join request can't slip in
    // with fewer paid entries than the team actually needs.
    const teamSize = Math.max(1, Number(tournament.teamSize || 1));
    // A player may enter a DUO/SQUAD alone or bring the full team. The
    // participant record still carries the match team size, while quantity
    // represents the number of slots this user is actually filling.
    if (quantity < 1 || quantity > teamSize) {
      return res.status(400).json({ error: `Select between 1 and ${teamSize} slot(s) for this team match` });
    }

    // Reserve this user's registration FIRST, atomically. The old code checked
    // "already registered" with a plain .once("value") read and only wrote the
    // participant record much later — if two requests landed close together
    // (double-tap on the join button, a retried network call, the same account
    // open on two devices) both could pass that read before either write
    // finished, then both claim a slot and both debit the wallet for what was
    // meant to be a single entry. A transaction on participantRef is how
    // Firebase guarantees only one concurrent request can "win" — every other
    // one is told already_registered before any coins move.
    let reservation = await participantRef.transaction((current) =>
      current == null ? { userId: decoded.uid, status: "RESERVING", reservedAt: Date.now() } : undefined);
    if (!reservation.committed) {
      // A "RESERVING" placeholder with no real registration behind it is a
      // ghost: the request that created it died before it could either
      // finish (write the real REGISTERED record) or clean up after itself
      // (e.g. the server process restarted mid-request during a deploy).
      // Without this check, that ghost blocks the player from ever joining
      // this match again — "already registered" forever, with 0 slots ever
      // actually filled. Any RESERVING record older than 2 minutes is safe
      // to treat as abandoned: reservation + debit + registration together
      // normally complete in well under a second.
      const existingStatus = String(reservation.snapshot?.val()?.status || "");
      const reservedAt = Number(reservation.snapshot?.val()?.reservedAt || 0);
      const isStaleGhost = existingStatus === "RESERVING" && (Date.now() - reservedAt) > 2 * 60 * 1000;
      if (isStaleGhost) {
        await participantRef.remove().catch(() => {});
        reservation = await participantRef.transaction((current) =>
          current == null ? { userId: decoded.uid, status: "RESERVING", reservedAt: Date.now() } : undefined);
      }
      if (!reservation.committed) {
        return res.status(200).json({ status: "already_registered" });
      }
    }
    reserved = true;

    const perSlotFee = Math.max(0, Number(tournament.entryFeeCoins || 0));

    // Warm-sync guard (root cause of "shows plenty of coins but still
    // rejects the join"): admin.database().ref(path).transaction() can invoke
    // its update callback SPECULATIVELY with current === null on its very
    // first attempt if this server process has never synced that exact path
    // before — which is common right after a Render cold start (the same
    // cold start the client already warns about as "Server is waking up").
    // The debit callback below correctly treats a genuinely-missing wallet as
    // 0 balance and aborts — but when that null is just "not synced yet" and
    // not the real value, the SDK is NOT allowed to retry, because returning
    // undefined from the callback is an explicit "abort the whole
    // transaction" signal, not a "try again" signal. A plain .once("value")
    // read on the exact same ref right before starting the transaction forces
    // a real round-trip to the database first, so by the time .transaction()
    // runs, the SDK already has the authoritative wallet object cached and
    // the callback's first invocation sees the real balance, not a stale null.
    await walletRef.once("value").catch(() => null);

    const claimedSlotNumbers = [];
    if (requestedSlots.length > 0) {
      // Specific slots picked by the player — claim every one of them, in
      // order. If any single slot in the batch is already taken, unwind
      // every slot claimed earlier in this same request before failing, so
      // a rejected multi-slot join never leaves the player holding a partial
      // set of slots they didn't ask to keep.
      for (const requested of requestedSlots) {
        const slotRef = db.ref(`tournaments/${tournamentId}/slotIndex/${requested}`);
        const slotClaim = await slotRef.transaction((current) => current == null ? decoded.uid : undefined);
        if (!slotClaim.committed) {
          for (const ref of claimedSlotRefs) await ref.transaction((current) => current === decoded.uid ? null : undefined).catch(() => {});
          await participantRef.remove().catch(() => {});
          return res.status(409).json({ error: `Slot ${requested} is already taken` });
        }
        claimedSlotRefs.push(slotRef);
        claimedSlotNumbers.push(requested);
      }
    } else {
      // Auto-assign: walk the slots in order and atomically claim the first
      // `quantity` free ones. Each transaction only succeeds for whichever
      // request gets there first, so simultaneous joins never collide on
      // the same slot, and a quantity > 1 just repeats the claim.
      for (let candidate = 1; candidate <= totalSlots && claimedSlotNumbers.length < quantity; candidate++) {
        const candidateRef = db.ref(`tournaments/${tournamentId}/slotIndex/${candidate}`);
        const attempt = await candidateRef.transaction((current) => current == null ? decoded.uid : undefined);
        if (attempt.committed) {
          claimedSlotNumbers.push(candidate);
          claimedSlotRefs.push(candidateRef);
        }
      }
      if (claimedSlotNumbers.length < quantity) {
        for (const ref of claimedSlotRefs) await ref.transaction((current) => current === decoded.uid ? null : undefined).catch(() => {});
        await participantRef.remove().catch(() => {});
        return res.status(409).json({ error: "This match is full" });
      }
    }

    // Entry fee is always per-slot × how many slots were actually claimed —
    // a 2-slot join costs exactly double a 1-slot join, never a flat rate.
    const entryFee = perSlotFee * claimedSlotNumbers.length;
    if (entryFee > 0) {
      // Debug trail for the "shows plenty of coins but still rejects the join"
      // reports — captures exactly what the transaction saw at the moment it
      // ran, since that's the one thing a post-failure re-read can't recover.
      let sawDuringTransaction = null;
      const debit = await walletRef.transaction((current) => {
        // FIX: first call gets a local guess (null). Returning undefined would
        // ABORT (not retry). Return null so Firebase fetches the real wallet and retries.
        if (current === null) return current;
        const wallet = current && typeof current === "object" ? { ...current } : {};
        const balance = Number(wallet.balance || 0);
        sawDuringTransaction = { rawCurrent: current, parsedBalance: balance };
        if (!Number.isFinite(balance) || balance < entryFee) return;
        wallet.balance = balance - entryFee;
        return wallet;
      });
      if (!debit.committed) {
        for (const ref of claimedSlotRefs) await ref.transaction((current) => current === decoded.uid ? null : undefined).catch(() => {});
        await participantRef.remove().catch(() => {});
        // Read the balance again (outside the failed transaction) purely to make
        // the error message useful — shows the player/admin the real gap between
        // what they have and what this join actually costs, instead of a bare
        // "not enough coins" that gives no way to tell a real shortfall from a
        // stale/cached balance shown somewhere on the client.
        const currentBalanceSnap = await walletRef.child("balance").once("value").catch(() => null);
        const currentBalance = currentBalanceSnap ? Number(currentBalanceSnap.val() || 0) : 0;
        console.error("joinMatch insufficient-balance debug", {
          uid: decoded.uid,
          tournamentId,
          entryFee,
          slots: claimedSlotNumbers,
          seenDuringDebitTransaction: sawDuringTransaction,
          balanceRereadAfterAbort: currentBalance,
        });
        return res.status(409).json({
          error: `You have ${currentBalance} coins but need ${entryFee} to join with ${claimedSlotNumbers.length} slot(s)`
        });
      }
      debitedAmount = entryFee;
    }

    const slotNumber = claimedSlotNumbers[0];
    // When per-slot players were provided, each claimed slot gets its own
    // {slotNumber, gameUid, gameName} entry (built from the map keyed by the
    // ORIGINAL requested slot numbers, which is safe here — usingPerSlotPlayers
    // only applies to the explicit-slots path, so claimedSlotNumbers is exactly
    // requestedSlots, just re-confirmed as actually claimed). The top-level
    // gameUid/gameName still mirror the first slot's player for any older
    // screen that only reads the flat fields.
    const slotPlayers = usingPerSlotPlayers
      ? claimedSlotNumbers.map((slot) => ({ slotNumber: slot, ...playersBySlot.get(slot) }))
      : null;
    const primaryPlayer = usingPerSlotPlayers ? playersBySlot.get(slotNumber) : { gameUid, gameName };
    const participant = {
      userId: decoded.uid, teamName: "", teamSize: Number(tournament.teamSize || 1),
      slotNumber, slotNumbers: claimedSlotNumbers,
      gameUid: primaryPlayer.gameUid, gameName: primaryPlayer.gameName,
      entryFeeCoins: entryFee, entryStatus: entryFee > 0 ? "PAID" : "FREE",
      status: "REGISTERED", createdAt: admin.database.ServerValue.TIMESTAMP,
    };
    if (slotPlayers) participant.players = slotPlayers;
    const updates = {};
    updates[`tournaments/${tournamentId}/participants/${decoded.uid}`] = participant;
    // joinedSlots used to only be set by hand in the admin panel and was never
    // touched here, so the "remaining slots" / FULL badge the app computes from
    // totalSlots - joinedSlots drifted from the real slotIndex claims (could
    // show FULL with open slots, or show open slots that were actually taken).
    // Increment it atomically as part of this same real-registration write —
    // by the full slot count, so a multi-slot join advances the fill bar the
    // same amount a matching number of single-slot joins would.
    updates[`tournaments/${tournamentId}/joinedSlots`] = admin.database.ServerValue.increment(claimedSlotNumbers.length);
    updates[`matches/${tournamentId}/joinedUsers/${decoded.uid}`] = participant;
    updates[`matches/${tournamentId}/participants/${decoded.uid}`] = participant;
    updates[`users/${decoded.uid}/lastGameName`] = primaryPlayer.gameName;
    if (entryFee > 0) {
      const txId = `entry_${tournamentId}_${decoded.uid}`;
      // Match the Transaction model's fields exactly (type/title/amount/timestamp/status),
      // the same way creditWalletOnce() does for top-ups. The previous version wrote
      // type: "MATCH_ENTRY" (not a value of the app's Transaction.Type enum) and left out
      // "status" entirely — the app's transactionFromLedger() silently drops any record
      // it can't parse, so every entry-fee deduction was invisible in Wallet history even
      // though the coins really were debited.
      const slotLabel = claimedSlotNumbers.length > 1 ? ` × ${claimedSlotNumbers.length} slots` : "";
      updates[`users/${decoded.uid}/wallet/transactions/${txId}`] = {
        type: "CONTEST_ENTRY",
        title: `Entry fee • ${String(tournament.title || "Match")}${slotLabel}`,
        amount: entryFee,
        timestamp: admin.database.ServerValue.TIMESTAMP,
        status: "SUCCESS",
      };
    }
    await db.ref().update(updates);
    // Slot numbers are only known for certain once every claim transaction
    // above has committed, so this is the first point the player can be
    // told which slot(s) they're in — the client shows it after this
    // response comes back, not before.
    return res.status(200).json({ status: "registered", entryFeeCoins: entryFee, slotNumber, slotNumbers: claimedSlotNumbers });
  } catch (error) {
    // Om's bug report: "join fails for whatever reason -> coin should NOT be
    // deducted and the slot should NOT show as full." The debit + slot claims
    // + final db.ref().update(updates) above are each awaited individually —
    // if db.ref().update() actually reached Firebase and committed on the
    // server, but the Node process then lost the connection before it got the
    // ack back (Render/Railway network hiccup, admin SDK socket drop, etc.),
    // the `await` throws here even though the write is already live. Blindly
    // rolling back in that situation is itself the bug: it would free the
    // slot back to "open" and refund the coins while a REGISTERED participant
    // record still sits there pointing at that same slot — exactly the
    // joinedSlots/slotIndex drift the reconciler further down this file has
    // to keep correcting for. So before undoing anything, re-read the
    // participant record straight from the database (bypassing whatever
    // just failed) and only compensate if it genuinely never got written.
    let alreadyRegistered = false;
    try {
      const recheck = await participantRef.once("value");
      alreadyRegistered = recheck.exists() && String(recheck.val()?.status || "") === "REGISTERED";
    } catch (_recheckError) {
      // Couldn't even confirm — fall through to the normal rollback below
      // rather than leaving the player in limbo with no response at all.
    }

    if (alreadyRegistered) {
      console.error("joinMatch post-error recheck: write had actually committed, skipping rollback", {
        uid: decoded.uid, tournamentId,
      });
      // Coins were correctly charged once and the slot(s) are correctly held —
      // report it as a success instead of telling the player it failed.
      const registeredSnap = await participantRef.once("value").catch(() => null);
      const registeredVal = registeredSnap ? registeredSnap.val() || {} : {};
      const finalSlots = Array.isArray(registeredVal.slotNumbers) && registeredVal.slotNumbers.length
        ? registeredVal.slotNumbers
        : [registeredVal.slotNumber].filter(Boolean);
      return res.status(200).json({
        status: "registered",
        entryFeeCoins: Number(registeredVal.entryFeeCoins || 0),
        slotNumber: registeredVal.slotNumber,
        slotNumbers: finalSlots,
      });
    }

    // Genuinely never registered — safe to undo every side effect so the
    // player ends up exactly where they started: full balance, slot open.
    if (debitedAmount > 0) {
      await walletRef.transaction((current) => {
        const wallet = current && typeof current === "object" ? { ...current } : {};
        wallet.balance = Number(wallet.balance || 0) + debitedAmount;
        return wallet;
      }).catch(() => {});
    }
    for (const ref of claimedSlotRefs) await ref.transaction((current) => current === decoded.uid ? null : undefined).catch(() => {});
    if (reserved) await participantRef.remove().catch(() => {});
    console.error("joinMatch error", error.message);
    return res.status(500).json({ error: "Could not complete match registration" });
  }
});

app.post("/zapupiWebhook", async (req, res) => {
  const orderId = req.body?.order_id || req.body?.data?.order_id;
  if (!orderId) return res.status(200).send("ok");
  // ZapUPI's dashboard test may send a placeholder order ID. It must receive
  // a successful HTTP response, but it must never be allowed to touch Firebase.
  if (!validOrderId(orderId)) return res.status(200).send("ok - test ping");

  try {
    const orderSnapshot = await db.ref(`orders/${orderId}`).once("value");
    const storedOrder = orderSnapshot.val();
    // Ignore unknown provider probes without creating a retry storm. Real
    // orders are always created in Firebase before ZapUPI is called.
    if (!storedOrder) return res.status(200).send("ok");

    await settleOrder(orderId, storedOrder);
    return res.status(200).send("ok");
  } catch (error) {
    console.error("webhook error", error.message);
    // Returning 200 avoids unbounded retries for a provider callback that we
    // have already logged. The order remains non-credited and can be retried
    // from the provider dashboard.
    return res.status(200).send("logged");
  }
});

// Called by the app itself the moment the user returns from the payment page
// (Custom Tab closed / app resumed). This is a same-second fallback so coins
// land immediately instead of waiting on ZapUPI's webhook delivery, which can
// be slow or, on a sleeping free-tier Render instance, missed entirely.
app.post("/verifyOrder", async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });

  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const orderId = String(req.body?.order_id || "").trim();
  if (!validOrderId(orderId)) return res.status(400).json({ error: "Invalid order id" });

  try {
    const orderSnapshot = await db.ref(`orders/${orderId}`).once("value");
    const storedOrder = orderSnapshot.val();
    if (!storedOrder) return res.status(404).json({ error: "Order not found" });
    // Only the user who created the order can trigger a settlement check for it.
    if (storedOrder.uid !== decoded.uid) return res.status(403).json({ error: "Order does not belong to this user" });

    const status = await settleOrder(orderId, storedOrder);
    return res.status(200).json({ status });
  } catch (error) {
    console.error("verifyOrder error", error.message);
    return res.status(500).json({ error: "Could not verify order right now. Please try again." });
  }
});

function validAmount(value, min, max) {
  return Number.isInteger(value) && value >= min && value <= max;
}

function validUpiId(value) {
  return typeof value === "string" && /^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$/.test(value.trim());
}

// User requests a payout. The client never touches wallet.locked or
// wallet.balance directly — this is now the only path that can move a
// withdrawal amount out of a user's spendable balance, matching the rules'
// users/$uid/wallet write restriction to RESULTS_COINS_ADMIN/MASTER_ADMIN
// (this endpoint uses the Admin SDK, which bypasses rules entirely).
app.post("/requestWithdrawal", moneyLimiter, async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });
  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const amount = Number(req.body?.amount);
  if (!validAmount(amount, 50, 100000)) {
    return res.status(400).json({ error: "Withdrawal amount must be between 50 and 100000 coins" });
  }
  const upiId = String(req.body?.upiId || "").trim();
  if (!validUpiId(upiId)) {
    return res.status(400).json({ error: "Enter a valid UPI ID (e.g. name@bank)" });
  }

  const uid = decoded.uid;
  let locked = false;
  try {
    // One pending request at a time — otherwise a user could fire several
    // requests before any is resolved and lock more coins than they hold.
    const existing = await db.ref("withdrawals").orderByChild("userId").equalTo(uid).once("value");
    let hasPending = false;
    existing.forEach((child) => { if (child.child("status").val() === "PENDING") hasPending = true; });
    if (hasPending) {
      return res.status(409).json({ error: "You already have a withdrawal request pending" });
    }

    const walletRef = db.ref(`users/${uid}/wallet`);
    // Same warm-sync guard as /joinMatch — see the comment there. Forces a
    // real read before the transaction so its callback never fires on a
    // stale/null local cache right after a Render cold start.
    await walletRef.once("value").catch(() => null);
    const debit = await walletRef.transaction((current) => {
      // FIX: same null-guess issue as joinMatch — return null so SDK retries with real data.
      if (current === null) return current;
      const wallet = current && typeof current === "object" ? { ...current } : {};
      const balance = Number(wallet.balance || 0);
      if (!Number.isFinite(balance) || balance < amount) return;
      wallet.balance = balance - amount;
      wallet.locked = Number(wallet.locked || 0) + amount;
      return wallet;
    });
    if (!debit.committed) {
      return res.status(409).json({ error: `You need ${amount} coins available to request this withdrawal` });
    }
    locked = true;

    const withdrawalId = db.ref("withdrawals").push().key;
    const txId = `withdrawal_${withdrawalId}`;
    const record = {
      userId: uid, amount, upiId, status: "PENDING",
      walletTxId: txId, createdAt: admin.database.ServerValue.TIMESTAMP,
    };
    await db.ref().update({
      [`withdrawals/${withdrawalId}`]: record,
      // Mirror under the user's own subtree so the app can show "my
      // withdrawals" for a normal user, who otherwise has no permission to
      // list the top-level withdrawals node (that's admin-only in the rules).
      [`users/${uid}/withdrawals/${withdrawalId}`]: record,
      [`users/${uid}/wallet/transactions/${txId}`]: {
        type: "WITHDRAWAL", title: `Withdrawal to ${upiId}`, amount,
        timestamp: admin.database.ServerValue.TIMESTAMP, status: "PENDING",
      },
    });
    await logActivity(uid, "WITHDRAWAL_REQUESTED", `${withdrawalId} • ${amount} coins`);
    return res.status(200).json({ withdrawalId, status: "PENDING" });
  } catch (error) {
    if (locked) {
      await db.ref(`users/${uid}/wallet`).transaction((current) => {
        const wallet = current && typeof current === "object" ? { ...current } : {};
        wallet.balance = Number(wallet.balance || 0) + amount;
        wallet.locked = Math.max(0, Number(wallet.locked || 0) - amount);
        return wallet;
      }).catch(() => {});
    }
    console.error("requestWithdrawal error", error.message);
    return res.status(500).json({ error: "Could not submit withdrawal request" });
  }
});

// Admin resolves a pending withdrawal. Replaces the old direct
// FirebaseRepository.updateWithdrawalStatus() client write, which only
// flipped the status string and never actually moved the locked coins.
app.post("/admin/approveWithdrawal", requireAdmin(isMasterOrPayment), async (req, res) => {
  const withdrawalId = String(req.body?.withdrawalId || "").trim();
  const action = String(req.body?.action || "").trim().toUpperCase();
  if (!withdrawalId || (action !== "PAID" && action !== "REJECTED")) {
    return res.status(400).json({ error: "withdrawalId and action ('PAID' or 'REJECTED') are required" });
  }

  try {
    const snapshot = await db.ref(`withdrawals/${withdrawalId}`).once("value");
    const withdrawal = snapshot.val();
    if (!withdrawal) return res.status(404).json({ error: "Withdrawal not found" });

    // Transaction guard so two admins tapping at once can't both resolve it. Also
    // self-heals a status stuck at PROCESSING — previously, ANY failure after this
    // claim (a bad FCM token, a network blip, etc.) left the record permanently
    // stuck here, since nothing ever set it back to PENDING; every retry then hit
    // "already resolved" forever even though the record was never actually paid or
    // rejected. A PROCESSING claim now expires after a couple of minutes so a
    // stuck record can be retried instead of dying silently.
    const STALE_PROCESSING_MS = 2 * 60 * 1000;
    const nowMs = Date.now();
    const claim = await db.ref(`withdrawals/${withdrawalId}`).transaction((current) => {
      if (!current) return current;
      if (current.status === "PENDING") {
        return { ...current, status: "PROCESSING", processingAt: nowMs };
      }
      if (current.status === "PROCESSING"
          && nowMs - Number(current.processingAt || 0) > STALE_PROCESSING_MS) {
        return { ...current, status: "PROCESSING", processingAt: nowMs };
      }
      return; // abort — genuinely mid-flight right now, or already PAID/REJECTED
    });
    if (!claim.committed) {
      return res.status(409).json({ error: "This withdrawal was already resolved" });
    }

    const { userId, amount, walletTxId } = withdrawal;
    await db.ref(`users/${userId}/wallet`).transaction((current) => {
      const wallet = current && typeof current === "object" ? { ...current } : {};
      wallet.locked = Math.max(0, Number(wallet.locked || 0) - Number(amount));
      if (action === "REJECTED") wallet.balance = Number(wallet.balance || 0) + Number(amount);
      return wallet;
    });

    const updates = {
      [`withdrawals/${withdrawalId}/status`]: action,
      [`withdrawals/${withdrawalId}/resolvedAt`]: admin.database.ServerValue.TIMESTAMP,
      [`withdrawals/${withdrawalId}/resolvedBy`]: req.decoded.uid,
      [`users/${userId}/withdrawals/${withdrawalId}/status`]: action,
      [`users/${userId}/withdrawals/${withdrawalId}/resolvedAt`]: admin.database.ServerValue.TIMESTAMP,
    };
    if (walletTxId) {
      updates[`users/${userId}/wallet/transactions/${walletTxId}/status`] =
        action === "PAID" ? "SUCCESS" : "FAILED";
    }
    await db.ref().update(updates);

    await createUserNotifications([userId],
      action === "PAID" ? "Withdrawal paid" : "Withdrawal rejected",
      action === "PAID"
        ? `Your withdrawal of ${amount} coins has been paid.`
        : `Your withdrawal of ${amount} coins was rejected and refunded to your wallet.`,
      "WITHDRAWAL_" + action, { withdrawalId });
    const token = (await db.ref(`users/${userId}/fcmToken`).once("value")).val();
    if (token) {
      await sendPush([token], action === "PAID" ? "Withdrawal paid" : "Withdrawal rejected",
        action === "PAID" ? `${amount} coins sent to your UPI ID.` : `${amount} coins refunded to your wallet.`,
        { type: "WITHDRAWAL_" + action, withdrawalId });
    }
    await logActivity(req.decoded.uid, "WITHDRAWAL_" + action, `${withdrawalId} • user ${userId} • ${amount} coins`);
    return res.status(200).json({ status: action });
  } catch (error) {
    // If we'd already claimed PROCESSING but failed before the status was flipped
    // to a final PAID/REJECTED (e.g. the wallet-locked-coins transaction threw),
    // put it straight back to PENDING instead of making the admin wait out the
    // stale-claim window above.
    await db.ref(`withdrawals/${withdrawalId}/status`)
      .transaction((current) => (current === "PROCESSING" ? "PENDING" : current))
      .catch(() => {});
    console.error("approveWithdrawal error", error.message);
    return res.status(500).json({ error: "Could not resolve withdrawal" });
  }
});

// Admin removes a resolved (PAID/REJECTED) withdrawal from the history list.
// PENDING requests can never be deleted here — they still hold locked coins,
// so they must go through approveWithdrawal (PAID/REJECTED) first, which is
// what actually releases or returns those coins. This only clears the
// already-settled record from both withdrawals/{id} and its
// users/{uid}/withdrawals/{id} mirror once nothing financial is left to do.
app.post("/admin/deleteWithdrawal", requireAdmin(isMasterOrPayment), async (req, res) => {
  const withdrawalId = String(req.body?.withdrawalId || "").trim();
  if (!withdrawalId) return res.status(400).json({ error: "withdrawalId is required" });

  try {
    const snapshot = await db.ref(`withdrawals/${withdrawalId}`).once("value");
    const withdrawal = snapshot.val();
    if (!withdrawal) return res.status(404).json({ error: "Withdrawal not found" });
    if (String(withdrawal.status || "").toUpperCase() === "PENDING") {
      return res.status(409).json({ error: "Resolve this request (Paid/Rejected) before deleting it" });
    }

    const updates = { [`withdrawals/${withdrawalId}`]: null };
    if (withdrawal.userId) updates[`users/${withdrawal.userId}/withdrawals/${withdrawalId}`] = null;
    await db.ref().update(updates);

    await logActivity(req.decoded.uid, "WITHDRAWAL_DELETED",
      `${withdrawalId} • user ${withdrawal.userId} • ${withdrawal.amount} coins`);
    return res.status(200).json({ status: "deleted" });
  } catch (error) {
    console.error("deleteWithdrawal error", error.message);
    return res.status(500).json({ error: "Could not delete withdrawal" });
  }
});

// Admin-facing: wipe every already-settled (PAID/REJECTED) withdrawal record
// in one go — used by the "Delete All History" button next to the per-row
// delete on the Payments > Withdrawals screen. Same PENDING guard as the
// single-delete route above: a still-open request is left untouched because
// it still holds locked coins and must be resolved first.
app.post("/admin/deleteAllWithdrawals", requireAdmin(isMasterOrPayment), async (req, res) => {
  try {
    const snapshot = await db.ref("withdrawals").once("value");
    if (!snapshot.exists()) return res.status(200).json({ status: "deleted", count: 0 });

    const updates = {};
    let count = 0;
    let skippedPending = 0;
    snapshot.forEach((child) => {
      const withdrawal = child.val() || {};
      if (String(withdrawal.status || "").toUpperCase() === "PENDING") {
        skippedPending += 1;
        return;
      }
      updates[`withdrawals/${child.key}`] = null;
      if (withdrawal.userId) updates[`users/${withdrawal.userId}/withdrawals/${child.key}`] = null;
      count += 1;
    });

    if (count > 0) await db.ref().update(updates);

    await logActivity(req.decoded.uid, "WITHDRAWAL_HISTORY_CLEARED",
      `${count} record(s) deleted${skippedPending ? `, ${skippedPending} pending kept` : ""}`);
    return res.status(200).json({ status: "deleted", count, skippedPending });
  } catch (error) {
    console.error("deleteAllWithdrawals error", error.message);
    return res.status(500).json({ error: "Could not delete withdrawal history" });
  }
});

// ADMIN PUSH RELAY — the Android ADMIN app calls this instead of talking to
// fcm.googleapis.com directly. Previously the admin APK needed
// assets/service-account.json (the Firebase private key!) bundled inside it,
// which was both missing from the repo and a security hole if it ever leaked.
// The service account now lives ONLY on Render, and the admin app just asks
// this endpoint to send the push on its behalf (authenticated via its
// Firebase ID token + active admin role, exactly like the other admin calls).
app.post("/admin/sendPush", requireAdmin(() => true), async (req, res) => {
  const rawTokens = Array.isArray(req.body?.tokens) ? req.body.tokens : [];
  const tokens = rawTokens.map((t) => String(t || "").trim()).filter(Boolean);
  const title = String(req.body?.title || "").trim();
  const body = String(req.body?.body || "").trim();
  const type = String(req.body?.type || "ROOM_RELEASED").trim();
  if (!tokens.length || !title) {
    return res.status(400).json({ error: "tokens and title are required" });
  }
  if (tokens.length > 500) {
    return res.status(400).json({ error: "Too many tokens (max 500 per call)" });
  }
  try {
    const response = await admin.messaging().sendEachForMulticast({
      tokens,
      notification: { title, body: body || title },
      data: { type },
    });
    await logActivity(req.decoded.uid, "ADMIN_PUSH",
      `${title} • ${response.successCount}/${tokens.length} devices • type=${type}`);
    return res.status(200).json({ sent: response.successCount, total: tokens.length });
  } catch (error) {
    console.error("sendPush relay error", error.message);
    return res.status(500).json({ error: "Could not send push" });
  }
});

// Lets a player back out of a match they haven't started yet: frees their
// slot(s), refunds the entry fee, and removes them from both the tournaments
// and matches mirrors (joinMatch above writes to both, so this undoes both).
app.post("/leaveMatch", moneyLimiter, async (req, res) => {
  const idToken = getBearerToken(req);
  if (!idToken) return res.status(401).json({ error: "Missing Firebase authorization token" });
  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (_error) {
    return res.status(401).json({ error: "Invalid Firebase authorization token" });
  }

  const tournamentId = String(req.body?.tournamentId || "").trim();
  if (!tournamentId) return res.status(400).json({ error: "tournamentId is required" });
  const uid = decoded.uid;

  try {
    const [tournamentSnap, participantSnap] = await Promise.all([
      db.ref(`tournaments/${tournamentId}`).once("value"),
      db.ref(`tournaments/${tournamentId}/participants/${uid}`).once("value"),
    ]);
    const tournament = tournamentSnap.val();
    const participant = participantSnap.val();
    if (!tournament) return res.status(404).json({ error: "Match not found" });
    if (!participant) return res.status(404).json({ error: "You are not registered in this match" });

    const startAt = Number(tournament.startAt || 0);
    const cutoff = 10 * 60 * 1000; // 10 minutes before start, matches the summary's leave window
    if (startAt > 0 && startAt - Date.now() < cutoff) {
      return res.status(409).json({ error: "Too close to match start to leave now" });
    }
    if (tournament.roomReleased === true || String(tournament.status || "").toUpperCase() === "COMPLETED") {
      return res.status(409).json({ error: "This match has already started" });
    }

    const slotNumbers = Array.isArray(participant.slotNumbers) && participant.slotNumbers.length
      ? participant.slotNumbers
      : (participant.slotNumber ? [participant.slotNumber] : []);
    const refund = Number(participant.entryFeeCoins || 0);

    for (const slot of slotNumbers) {
      await db.ref(`tournaments/${tournamentId}/slotIndex/${slot}`)
        .transaction((current) => current === uid ? null : undefined);
    }
    const updates = {
      [`tournaments/${tournamentId}/participants/${uid}`]: null,
      [`tournaments/${tournamentId}/joinedSlots`]: admin.database.ServerValue.increment(-slotNumbers.length),
      [`matches/${tournamentId}/joinedUsers/${uid}`]: null,
      [`matches/${tournamentId}/participants/${uid}`]: null,
    };
    if (refund > 0) {
      const txId = `leave_${tournamentId}_${uid}_${Date.now()}`;
      updates[`users/${uid}/wallet/transactions/${txId}`] = {
        type: "REFUND", title: `Left match • ${String(tournament.title || "Match")}`,
        amount: refund, timestamp: admin.database.ServerValue.TIMESTAMP, status: "SUCCESS",
      };
    }
    await db.ref().update(updates);
    if (refund > 0) {
      await db.ref(`users/${uid}/wallet`).transaction((current) => {
        const wallet = current && typeof current === "object" ? { ...current } : {};
        wallet.balance = Number(wallet.balance || 0) + refund;
        return wallet;
      });
    }
    await logActivity(uid, "MATCH_LEFT", `${tournamentId} • refunded ${refund} coins`);
    return res.status(200).json({ status: "left", refundedCoins: refund });
  } catch (error) {
    console.error("leaveMatch error", error.message);
    return res.status(500).json({ error: "Could not leave the match" });
  }
});

// These workers are additive and do not touch the existing ZapUPI settlement path.
setInterval(processRoomReleases, 15_000);
setInterval(processBroadcasts, 15_000);
setInterval(processCoinApprovals, 15_000);
processRoomReleases();
processBroadcasts();
processCoinApprovals();

// SLOT RECONCILER — joinedSlots used to drift from the real slotIndex claims
// (admin hand-edits, partial legacy joins, mirrored writes). Every minute the
// server recounts tournaments/{id}/slotIndex and repairs any mismatch so the
// fill bar / "spots left" / FULL badge on every device stays truthful.
let slotReconcilerRunning = false;
async function reconcileJoinedSlots() {
  if (slotReconcilerRunning) return;
  slotReconcilerRunning = true;
  try {
    const snapshot = await db.ref("tournaments").once("value");
    snapshot.forEach((matchSnapshot) => {
      const claimed = matchSnapshot.child("slotIndex").numChildren();
      const current = Number(matchSnapshot.child("joinedSlots").val() || 0);
      if (Number.isInteger(claimed) && claimed >= 0 && claimed !== current) {
        db.ref(`tournaments/${matchSnapshot.key}/joinedSlots`).set(claimed).catch(() => {});
      }
    });
  } catch (error) {
    console.error("slot reconciler error", error.message);
  } finally {
    slotReconcilerRunning = false;
  }
}
setInterval(reconcileJoinedSlots, 60_000);
reconcileJoinedSlots();

// Render's free plan puts the service to sleep after ~15 minutes with no
// inbound traffic; the next real request then pays a 30-60s cold-start
// penalty (this is what the app's join/payment timeouts were widened for).
// A self-ping to our own public /health endpoint every few minutes counts
// as inbound traffic, so it keeps the free-plan instance warm without
// needing an external cron service. Harmless on a paid plan too — it's a
// tiny periodic GET. Set KEEP_ALIVE=false to disable it entirely (e.g. once
// on a paid plan where sleeping is no longer possible).
if ((process.env.KEEP_ALIVE || "true").trim().toLowerCase() !== "false") {
  const KEEP_ALIVE_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes, comfortably under the 15-minute sleep window
  setInterval(() => {
    fetch(`${publicBaseUrl}/health`).catch(() => {
      // A missed ping just means one skipped keep-alive tick; the next
      // real user request still works, it just may pay the cold-start cost.
    });
  }, KEEP_ALIVE_INTERVAL_MS);
}

app.listen(port, () => {
  console.log(`STARX24 payment backend listening on port ${port}`);
});
