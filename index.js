/**
 * STARX24 — ZapUPI secure backend (Render.com free-tier version)
 * ---------------------------------------------------------------
 * Same logic as the Firebase Functions version, but runs as a plain
 * Express server — deployable on Render's free tier, no Firebase
 * billing (Blaze plan) required.
 *
 * It still talks to your existing Firebase Realtime Database for the
 * wallet balance, using a Service Account key (not the app's Firebase
 * config) — this key gives full admin access, so it lives only here,
 * as an environment variable on Render. Never put it in the Android app.
 * ---------------------------------------------------------------
 */

const express = require("express");
const admin = require("firebase-admin");
const fetch = require("node-fetch");

// Service account JSON is pasted into Render's env var FIREBASE_SERVICE_ACCOUNT
const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DATABASE_URL, // e.g. https://your-project-default-rtdb.firebaseio.com
});
const db = admin.database();

const ZAPUPI_KEY = process.env.ZAPUPI_KEY;
const ZAPUPI_MODE = process.env.ZAPUPI_MODE || "TEST";

const CREATE_ORDER_URL = "https://pay.zapupi.com/api/create-order";
const ORDER_STATUS_URL = "https://pay.zapupi.com/api/order-status";

const app = express();
app.use(express.json());

// ---------------------------------------------------------------
// 1. CREATE ORDER
// ---------------------------------------------------------------
app.post("/createOrder", async (req, res) => {
  if (ZAPUPI_MODE !== "TEST") {
    return res.status(403).json({ error: "Live mode is disabled until compliance checks are done." });
  }

  const authHeader = req.headers.authorization || "";
  const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : null;
  if (!idToken) return res.status(401).json({ error: "Missing Authorization: Bearer <idToken>" });

  let uid;
  try {
    const decoded = await admin.auth().verifyIdToken(idToken);
    uid = decoded.uid;
  } catch (e) {
    return res.status(401).json({ error: "Invalid ID token" });
  }

  const amount = parseInt(req.body.amount, 10);
  if (!Number.isInteger(amount) || amount < 1 || amount > 100000) {
    return res.status(400).json({ error: "Invalid amount" });
  }
  const mobile = (req.body.mobile || "").toString().trim();

  const orderId = `STARX${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const webhookUrl = `${req.protocol}://${req.get("host")}/zapupiWebhook`;

  try {
    await db.ref(`orders/${orderId}`).set({
      uid,
      amount,
      status: "pending",
      createdAt: admin.database.ServerValue.TIMESTAMP,
    });

    const zapRes = await fetch(CREATE_ORDER_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        zap_key: ZAPUPI_KEY,
        order_id: orderId,
        amount: String(amount),
        customer_mobile: mobile || undefined,
        remark: `STARX24 wallet | ${orderId}`,
        webhook_url: webhookUrl,
      }),
    });

    const zapData = await zapRes.json();
    if (zapData.status !== "success" || !zapData.payment_url) {
      await db.ref(`orders/${orderId}/status`).set("create_failed");
      console.error("ZapUPI create-order failed", zapData);
      return res.status(502).json({ error: "Could not create ZapUPI order" });
    }

    return res.status(200).json({ order_id: orderId, payment_url: zapData.payment_url });
  } catch (e) {
    console.error("createOrder error", e);
    return res.status(500).json({ error: "Internal error creating order" });
  }
});

// ---------------------------------------------------------------
// 2. WEBHOOK
// ---------------------------------------------------------------
// ZapUPI dashboard's "Test" button may ping with GET — respond OK so the
// dashboard test passes.
app.get("/zapupiWebhook", (req, res) => res.status(200).send("ok - webhook endpoint reachable"));

app.post("/zapupiWebhook", async (req, res) => {
  try {
    const orderId = req.body.order_id || req.body.data?.order_id;
    // ZapUPI's dashboard "Test" button pings this URL without a real
    // order_id just to check for an HTTP 200 — respond OK instead of
    // erroring, so the dashboard test passes. Only real webhook calls
    // (which always include order_id) go through the logic below.
    if (!orderId) return res.status(200).send("ok - no order_id (test ping)");

    const orderSnap = await db.ref(`orders/${orderId}`).once("value");
    const order = orderSnap.val();
    if (!order) return res.status(404).send("unknown order");

    if (order.status === "credited" || order.status === "failed") {
      return res.status(200).send("already processed");
    }

    const statusRes = await fetch(ORDER_STATUS_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ zap_key: ZAPUPI_KEY, order_id: orderId }),
    });
    const statusData = await statusRes.json();
    const verifiedStatus = statusData?.data?.status;

    if (verifiedStatus === "Success") {
      await db.ref(`orders/${orderId}/status`).transaction((current) => {
        if (current === "pending") return "crediting";
        return;
      });

      const freshSnap = await db.ref(`orders/${orderId}`).once("value");
      const fresh = freshSnap.val();
      if (fresh.status === "crediting") {
        await db.ref(`users/${order.uid}/wallet/balance`).transaction((bal) => (bal || 0) + order.amount);
        await db.ref(`orders/${orderId}`).update({ status: "credited", creditedAt: admin.database.ServerValue.TIMESTAMP });
        console.log(`Credited order ${orderId} to user ${order.uid}: +${order.amount}`);
      }
    } else if (verifiedStatus === "Failed") {
      await db.ref(`orders/${orderId}/status`).set("failed");
    }

    return res.status(200).send("ok");
  } catch (e) {
    console.error("webhook error", e);
    return res.status(200).send("logged error");
  }
});

app.get("/", (req, res) => res.send("STARX24 ZapUPI backend is running."));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`Server running on port ${PORT}`));
