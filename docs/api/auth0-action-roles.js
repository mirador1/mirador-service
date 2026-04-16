/**
 * Auth0 Action — Inject user roles into access token
 *
 * PURPOSE:
 * The Mirador backend reads roles from the custom claim "https://mirador-api/roles"
 * in JWT access tokens. Auth0 does not include roles by default — this Action
 * reads the user's assigned Auth0 roles and embeds them in every access token.
 *
 * SETUP:
 * 1. Auth0 Dashboard → User Management → Roles
 *    Create roles: ROLE_ADMIN, ROLE_USER, ROLE_READER
 *
 * 2. Auth0 Dashboard → User Management → Users
 *    Select your user → Roles → Assign role ROLE_ADMIN (or ROLE_USER, ROLE_READER)
 *
 * 3. Auth0 Dashboard → Actions → Flows → Login
 *    → Add Action → Build from scratch
 *    Paste this code, click Deploy, then drag the Action into the Login flow.
 *
 * RESULT:
 * Access tokens will contain:
 *   {
 *     "https://mirador-api/roles": ["ROLE_ADMIN"],
 *     "aud": ["https://mirador-api", "https://<domain>/userinfo"],
 *     ...
 *   }
 *
 * The backend (JwtAuthenticationFilter.authenticateKeycloak) reads this claim
 * and grants the corresponding Spring Security authorities.
 *
 * Role mapping:
 *   ROLE_ADMIN  → full access (read, write, delete, admin endpoints)
 *   ROLE_USER   → read + write (cannot delete)
 *   ROLE_READER → read-only (GET endpoints only)
 */
exports.onExecutePostLogin = async (event, api) => {
  // Namespace must match backend's JwtAuthenticationFilter claim key
  const namespace = 'https://mirador-api';

  // event.authorization.roles contains the Auth0 roles assigned to this user.
  // Requires the "read:roles" grant — automatically available in Actions.
  const roles = event.authorization?.roles ?? [];

  if (roles.length > 0) {
    // Inject into the access token (sent to the backend API)
    api.accessToken.setCustomClaim(`${namespace}/roles`, roles);
    // Also inject into the ID token (readable by the Angular frontend)
    api.idToken.setCustomClaim(`${namespace}/roles`, roles);
  }
};
