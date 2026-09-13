/**
 * One-time repair script.
 *
 * Before this fix, `tournaments/{id}/joinedSlots` was only ever set by hand
 * from the Admin panel and was never updated when a real user joined via
 * /joinMatch. So any tournament created (or edited) before this fix can have
 * a joinedSlots value that no longer matches reality — showing "FULL" with
 * open slots, or showing open slots that are actually taken.
 *
 * This script recomputes joinedSlots for every tournament from the real
 * source of truth (the participants actually registered) and corrects it.
 * Run it ONCE after deploying the /joinMatch fix, then it's not needed again
 * — going forward, joinMatch keeps joinedSlots in sync automatically.
 *
 * Usage (from app/arenax-android/backend):
 *   FIREBASE_SERVICE_ACCOUNT=... FIREBASE_DATABASE_URL=... node scripts/resyncJoinedSlots.js
 * (or FIREBASE_SERVICE_ACCOUNT_BASE64=... instead of the raw JSON var)
 * Use the same values already configured on Render for this backend.
 */

const admin = require("firebase-admin");

function readServiceAccount() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT?.trim();
  const base64 = process.env.FIREBASE_SERVICE_ACCOUNT_BASE64?.trim();
  const encoded = base64 ? Buffer.from(base64, "base64").toString("utf8") : raw;
  if (!encoded) throw new Error("Missing FIREBASE_SERVICE_ACCOUNT (or _BASE64)");
  const serviceAccount = JSON.parse(encoded);
  return { ...serviceAccount, private_key: serviceAccount.private_key.replace(/\\n/g, "\n") };
}

async function main() {
  const serviceAccount = readServiceAccount();
  const databaseURL = process.env.FIREBASE_DATABASE_URL?.trim()
    || `https://${serviceAccount.project_id}-default-rtdb.firebaseio.com`;

  admin.initializeApp({ credential: admin.credential.cert(serviceAccount), databaseURL });
  const db = admin.database();

  const snapshot = await db.ref("tournaments").once("value");
  if (!snapshot.exists()) {
    console.log("No tournaments found.");
    return;
  }

  const updates = {};
  let changed = 0;
  let checked = 0;

  snapshot.forEach((child) => {
    checked += 1;
    const tournament = child.val() || {};
    const realCount = tournament.participants ? Object.keys(tournament.participants).length : 0;
    const storedCount = Number(tournament.joinedSlots || 0);
    if (realCount !== storedCount) {
      updates[`${child.key}/joinedSlots`] = realCount;
      console.log(`${child.key}: joinedSlots ${storedCount} -> ${realCount}`);
      changed += 1;
    }
  });

  if (changed > 0) {
    await db.ref("tournaments").update(updates);
  }

  console.log(`Checked ${checked} tournaments, corrected ${changed}.`);
  process.exit(0);
}

main().catch((error) => {
  console.error("resyncJoinedSlots failed:", error.message);
  process.exit(1);
});

