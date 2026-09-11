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

app.post("/createOrder", async (req, res) => {
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

// These workers are additive and do not touch the existing ZapUPI settlement path.
setInterval(processRoomReleases, 15_000);
setInterval(processBroadcasts, 15_000);
setInterval(processCoinApprovals, 15_000);
processRoomReleases();
processBroadcasts();
processCoinApprovals();

app.listen(port, () => {
  console.log(`STARX24 payment backend listening on port ${port}`);
});
