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

async function createZapupiOrder(orderId, amount, mobile) {
  const response = await fetch(CREATE_ORDER_URL, {
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
  if (!Number.isInteger(amount) || amount < 1 || amount > 100000) {
    return res.status(400).json({ error: "Invalid amount" });
  }

  const mobile = String(req.body?.mobile || "").trim();
  if (mobile && !/^[0-9+() -]{7,20}$/.test(mobile)) {
    return res.status(400).json({ error: "Invalid mobile number" });
  }

  const orderId = `STARX${Date.now()}_${randomBytes(3).toString("hex")}`;
  const orderRef = db.ref(`orders/${orderId}`);

  try {
    await orderRef.set({
      uid: decoded.uid,
      amount,
      status: "pending",
      mode: zapupiMode,
      createdAt: admin.database.ServerValue.TIMESTAMP,
    });

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
    console.error("createOrder error", error.message);
    await orderRef.update({ status: "create_failed" }).catch(() => {});
    return res.status(500).json({ error: "Payment service could not save the order. Please try again." });
  }
});

// ZapUPI dashboard test pings can be GET requests.
app.get("/zapupiWebhook", (_req, res) => res.status(200).send("ok"));

async function getVerifiedOrderStatus(orderId) {
  const response = await fetch(ORDER_STATUS_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ zap_key: zapupiKey, order_id: orderId }),
  });
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
  return uids;
}

async function createUserNotifications(uids, title, description, type, data = {}) {
  const updates = {};
  for (const uid of uids) {
    const notificationId = db.ref(`users/${uid}/notifications`).push().key;
    if (!notificationId) continue;
    updates[`users/${uid}/notifications/${notificationId}`] = {
      title,
      description,
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
    const snapshot = await db.ref("broadcast_notifications").once("value");
    const jobs = [];
    snapshot.forEach((item) => {
      if (item.child("pushSent").val() !== true) jobs.push(item);
    });
    for (const item of jobs) {
      const claimed = await db.ref(`broadcast_notifications/${item.key}/pushSent`)
        .transaction((current) => current === true ? undefined : true);
      if (!claimed.committed) continue;
      const title = String(item.child("title").val() || "ArenaX notification");
      const description = String(item.child("description").val() || "");
      const tokens = await getAllUserTokens();
      const users = await db.ref("users").once("value");
      const uids = [];
      users.forEach((user) => { if (user.key) uids.push(user.key); });
      await createUserNotifications(uids, title, description, "BROADCAST");
      await sendPush(tokens, title, description, { type: "BROADCAST", notificationId: item.key });
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
    if (teamSize > 1 && quantity !== teamSize) {
      return res.status(400).json({ error: `This is a ${teamSize}-player team match — select exactly ${teamSize} slots` });
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
    const reservation = await participantRef.transaction((current) =>
      current == null ? { userId: decoded.uid, status: "RESERVING" } : undefined);
    if (!reservation.committed) {
      return res.status(200).json({ status: "already_registered" });
    }
    reserved = true;

    const perSlotFee = Math.max(0, Number(tournament.entryFeeCoins || 0));

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
      const debit = await walletRef.transaction((current) => {
        const wallet = current && typeof current === "object" ? { ...current } : {};
        const balance = Number(wallet.balance || 0);
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
    const debit = await walletRef.transaction((current) => {
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

    // Transaction guard so two admins tapping at once can't both resolve it.
    const claim = await db.ref(`withdrawals/${withdrawalId}/status`)
      .transaction((current) => current === "PENDING" ? "PROCESSING" : undefined);
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
    console.error("approveWithdrawal error", error.message);
    return res.status(500).json({ error: "Could not resolve withdrawal" });
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
