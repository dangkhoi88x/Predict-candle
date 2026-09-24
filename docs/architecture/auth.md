# Auth and roles

Wallet and Telegram sign-in, the session tokens, and why admin is configuration rather than data.

## Auth

Wallet-signature login, no passwords. `GET /wallet/nonce` → client signs it → `POST
/wallet/verify` → server recovers the address and issues a session.

Access token lives **in memory only** (never localStorage); the refresh token is an HttpOnly
cookie, so `auth.js` silently POSTs `/api/auth/refresh` on load to restore a session.
`JwtAuthenticationFilter` authenticates the request as a bare `Long` principal (much of the
codebase pattern-matches on that) plus one authority, `ROLE_USER` or `ROLE_ADMIN`.

Since anyone can connect a wallet, **`.authenticated()` alone means "everyone"** on this app.
Anything narrower uses the role.

## Roles

Two: `USER` and `ADMIN`, on `users.role`. Who is an admin comes from `candles.admin.wallets`
(env `ADMIN_WALLETS`), not from the database — `AdminRoleReconciler` promotes listed wallets
and **demotes** unlisted admins at every startup, and `AuthService` promotes at login for a
listed wallet that has never signed in. So an admin screen cannot grant the role; editing the
config and restarting is the only way, which is deliberate (revoking has to be as easy as
granting). Change that class if that trade stops being worth it.

The role travels in the access token, so `hasRole(...)` in `SecurityConfig` costs no query.
That claim is a snapshot up to 15 minutes stale, so **anything that writes calls
`AdminAccess.requireAdmin()`**, which re-reads the role from the database. `User.assignRole`
also bumps `tokenVersion`, killing the account's refresh tokens on any role change.
