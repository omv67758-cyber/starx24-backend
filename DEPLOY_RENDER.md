# STARX24 ZapUPI Backend — Render.com (Free, no card needed)

## 1. GitHub pe code push karo
Render seedha zip upload nahi accept karta — GitHub repo se deploy hota hai.
Termux mein:
```bash
pkg install git -y
cd starx24_zapupi_backend_render
git init
git add .
git commit -m "zapupi backend"
```
Fir GitHub.com pe (mobile browser se) ek naya **private repo** banao (e.g. `starx24-backend`), aur uske instructions follow karke push karo:
```bash
git remote add origin https://github.com/<your-username>/starx24-backend.git
git branch -M main
git push -u origin main
```
(GitHub username/password ke bajaye ab **Personal Access Token** chahiye hoga login ke liye — GitHub Settings → Developer Settings → Personal Access Tokens se bana lena, password ki jagah wahi paste karna)

## 2. Render.com pe account banao
- https://render.com pe jao, **"Sign up with GitHub"** se login karo (koi card nahi chahiye)

## 3. Naya Web Service banao
- Dashboard → **New +** → **Web Service**
- Apna `starx24-backend` repo select karo
- Settings:
  - **Runtime:** Node
  - **Build Command:** `npm install`
  - **Start Command:** `npm start`
  - **Instance Type:** **Free**

## 4. Environment variables daalo (Render dashboard mein "Environment" tab)
| Key | Value |
|---|---|
| `ZAPUPI_KEY` | apni Test Zap Key |
| `ZAPUPI_MODE` | `TEST` |
| `FIREBASE_DATABASE_URL` | Firebase console → Realtime Database → top pe URL milega |
| `FIREBASE_SERVICE_ACCOUNT` | neeche step 5 dekho |

## 5. Firebase Service Account key generate karo
- Firebase Console → Project Settings (⚙️) → **Service Accounts** tab → **Generate new private key** → ek `.json` file download hogi
- Us poori JSON file ka content copy karo (`{` se `}` tak sab kuch)
- Render ke `FIREBASE_SERVICE_ACCOUNT` env var mein **poora JSON ek hi line mein paste** karo

⚠️ Ye service account key bahut powerful hai (poore Firebase project ka full access deti hai) — sirf Render ke env var mein rakho, kabhi GitHub repo mein commit mat karna, kabhi Android app mein mat daalna.

## 6. Deploy
"Create Web Service" dabao — 2-3 minute mein live ho jayega. URL milega jaise:
```
https://starx24-backend.onrender.com
```
Webhook URL hoga: `https://starx24-backend.onrender.com/zapupiWebhook`
Isko ZapUPI dashboard → Developers → API Key & Webhook mein paste karo.

## Free tier ki ek limitation
Render ka free tier 15 min inactivity ke baad server ko "sleep" kar deta hai — agle request pe usse wake hone mein ~30-50 second lagte hain (pehla payment thoda slow lagega, baad wale fast). Tournament app ke test/early stage ke liye ye chalega; jab real users aane lagein tab paid tier (~$7/month) consider karna, kyunki sleeping backend payment flow ke beech mein delay create kar sakta hai.
