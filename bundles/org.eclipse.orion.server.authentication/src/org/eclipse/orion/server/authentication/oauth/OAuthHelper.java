/*******************************************************************************
 * Copyright (c) 2014 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.authentication.oauth;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest.AuthenticationRequestBuilder;
import org.apache.oltu.oauth2.client.response.OAuthAccessTokenResponse;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.utils.OAuthUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.orion.server.authentication.Activator;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.PreferenceHelper;
import org.eclipse.orion.server.core.ServerConstants;
import org.eclipse.orion.server.core.events.IEventService;
import org.eclipse.orion.server.user.profile.IOrionUserProfileConstants;
import org.eclipse.orion.server.user.profile.IOrionUserProfileNode;
import org.eclipse.orion.server.user.profile.IOrionUserProfileService;
import org.eclipse.orion.server.useradmin.IOrionCredentialsService;
import org.eclipse.orion.server.useradmin.User;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups methods to handle session attributes for OAuth authentication.
 * @author Aidan Redpath
 *
 */
public class OAuthHelper {

	public static final String OAUTH = "oauth"; //$NON-NLS-1$
	public static final String REDIRECT_TYPE = "redirect_type"; //$NON-NLS-1$
	static final String OAUTH_IDENTIFIER = "oauth_identifier"; //$NON-NLS-1$
	static final String OAUTH_DISC = "oauth-disc"; //$NON-NLS-1$
	private static IOrionCredentialsService userAdmin;

	private static IOrionUserProfileService userProfileService;
	private static IEventService eventService;

	/**
	 * Redirects the user to the appropriate oauth token provider authentication page.
	 * @param req The request of to the server.
	 * @param resp The response from the server.
	 * @param oauthParams The oath parameters used to build the appropriate uri.
	 * @throws OAuthException Throw if there is an error build the request or if there is a problem sending the redirect.
	 */
	public static void redirectToOAuthProvider(HttpServletRequest req, HttpServletResponse resp, OAuthParams oauthParams) throws OAuthException {
		try {
			AuthenticationRequestBuilder requestBuilder = OAuthClientRequest
					.authorizationProvider(oauthParams.getProviderType())
					.setClientId(oauthParams.getClientKey())
					.setRedirectURI(oauthParams.getRedirectURI())
					.setResponseType(oauthParams.getResponseType())
					.setScope(oauthParams.getScope())
					.setState(oauthParams.getState());
			oauthParams.addAdditionsParams(requestBuilder);
			OAuthClientRequest request = requestBuilder.buildQueryMessage();
			resp.sendRedirect(request.getLocationUri());
		} catch (OAuthSystemException e) {
			// Error building request
			throw new OAuthException(e);
		} catch (IOException e) {
			// Error with sending redirect
			throw new OAuthException(e);
		}
	}

	/**
	 * Verifies the oauth authorization code is valid by sending it to the authorization server.
	 * @param req The request of to the server.
	 * @param resp The response from the server.
	 * @param oauthParams The oath parameters used to build the appropriate uri.
	 * @return Returns the oauth consumer related to the authorization code.
	 * @throws OAuthException Thrown if any error occurs during execution.
	 */
	public static OAuthConsumer handleOAuthReturnAndTokenAccess(HttpServletRequest req, HttpServletResponse resp, OAuthParams oauthParams) throws OAuthException {
		// Check if the server response contains an error message
		String error = req.getParameter("error");
		if(error != null)
			throw new OAuthException(error);

		String state = req.getParameter("state");
		if (state == null || !state.equals(oauthParams.getState())) {
			throw new OAuthException("The OAuth states do not match. Token provided by an unauthorized third party.");
		}

		// Get the authorization code
		String code = req.getParameter("code");
		if (code == null)
			throw new OAuthException("No code provided");

		try {
			OAuthClientRequest request = OAuthClientRequest
					.tokenProvider(oauthParams.getProviderType())
					.setGrantType(oauthParams.getGrantType())
					.setClientId(oauthParams.getClientKey())
					.setClientSecret(oauthParams.getClientSecret())
					.setRedirectURI(oauthParams.getRedirectURI())
					.setCode(code)
					.buildBodyMessage();

			OAuthClient oAuthClient = new OAuthClient(new URLConnectionClient());

			// Send request to oauth server
			OAuthAccessTokenResponse oauthAccessTokenResponse = oAuthClient.accessToken(request, oauthParams.getTokenResponseClass());

			OAuthConsumer consumer = oauthParams.getNewOAuthConsumer(oauthAccessTokenResponse);
			return consumer;
		} catch (OAuthSystemException e) {
			// Error building request
			throw new OAuthException(e);
		} catch (OAuthProblemException e) {
			// Error getting access token
			throw new OAuthException(e);
		}
	}

	/**
	 * Method to try and authenticate an oauth consumer.
	 * @param req The request of to the server.
	 * @param resp The response from the server.
	 * @param oauthConsumer The current authorized oauth consumer.
	 * @throws OAuthException Thrown if there is a problem authenticating the user.
	 * @throws IOException Throw if there is a problem sending the redirect.
	 */
	public static void handleLogin(HttpServletRequest req, HttpServletResponse resp, OAuthConsumer oauthConsumer) throws OAuthException, IOException {
		if(oauthConsumer == null || OAuthUtils.isEmpty(oauthConsumer.getIdentifier())) {
			throw new OAuthException("There is no Orion account associated with this Id. Please register or contact your system administrator for assistance.");
		}
		String redirect = oauthConsumer.getRedirect();
		User user = getUser(oauthConsumer);
		if (user == null) {
			String url = "/mixloginstatic/LoginWindow.html";
			url += "?oauth=create&email=" + oauthConsumer.getEmail();
			url += "&username=" + oauthConsumer.getUsername();
			url += "&identifier=" + oauthConsumer.getIdentifier();
			url += "&redirect=" + redirect;
			resp.sendRedirect(url);
			return;
		}

		req.getSession().setAttribute("user", user.getUid()); //$NON-NLS-1$

		if (getEventService() != null) {
			JSONObject message = new JSONObject();
			try {
				SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
				Date date = new Date(System.currentTimeMillis());
				message.put("event", "login");
				message.put("published", format.format(date));
				message.put("user", user.getUid());
			} catch (JSONException e1) {
				LogHelper.log(e1);
			}
			getEventService().publish("orion/login", message);
		}

		IOrionUserProfileNode userProfileNode = getUserProfileService().getUserProfileNode(user.getUid(), IOrionUserProfileConstants.GENERAL_PROFILE_PART);
		try {
			// try to store the login timestamp in the user profile
			userProfileNode.put(IOrionUserProfileConstants.LAST_LOGIN_TIMESTAMP, new Long(System.currentTimeMillis()).toString(), false);
			userProfileNode.flush();
		} catch (CoreException e) {
			// just log that the login timestamp was not stored
			LogHelper.log(e);
		}

		if (redirect != null) {
			resp.sendRedirect(redirect);
			return;
		}else{
			resp.sendRedirect("/index.html");
		}

		return;
	}

	private static User getUser(OAuthConsumer oauthConsumer){
		// Get user by oauth property
		Set<User> users = userAdmin.getUsersByProperty("oauth", ".*\\Q" + oauthConsumer.getIdentifier() + "\\E.*", true, false);
		if (users.size() > 0) {
			return users.iterator().next();
		}
		// Might need migration, try openid property
		String openidIdentifier = oauthConsumer.getOpenidIdentifier();
		if(openidIdentifier == null)
			return null;
		users = userAdmin.getUsersByProperty("openid", ".*\\Q" + openidIdentifier + "\\E.*", true, false);
		if (users.size() > 0) {
			User user = users.iterator().next();
			// Remove old property
			String openidsString = (String) user.getProperty("openid");
			String [] openIds = openidsString.split("\n");
			if (openIds.length == 1) {
				user.removeProperty("openid");
			} else {
				// Multiple open id's
				// Remove the current one
				String newOpenIds = "";
				for (String openid : openIds) {
					if (!openid.equals(oauthConsumer.getOpenidIdentifier())) {
						newOpenIds += openid + "\n";
					}
				}
				newOpenIds = newOpenIds.substring(0, newOpenIds.length() - 1);
				user.addProperty("openid", newOpenIds);
			}
			// Add new property
			String oauths = (String) user.getProperty("oauth");
			if(oauths != null && !oauths.equals("")){
				oauths += '\n';
			} else {
				oauths = "";
			}
			oauths += oauthConsumer.getIdentifier();
			user.addProperty("oauth", oauths);
			userAdmin.updateUser(user.getUid(), user);
			return user;
		}
		return null;
	}

	/**
	 * Prepared javascript to send to the user to link the oauth consumer to the current user.
	 * @param req The request of to the server.
	 * @param resp The response from the server.
	 * @param oauthConsumer The current authorized oauth consumer.
	 * @throws IOException Thrown if there is a problem access the response writer.
	 * @throws OAuthException Thrown if the authentication response is not sufficient.
	 */
	public static void handleReturnAndLinkAccount(HttpServletRequest req, HttpServletResponse resp, OAuthConsumer oauthConsumer) throws IOException, OAuthException {
		if (oauthConsumer != null) {
			String id = oauthConsumer.getIdentifier();
			if (OAuthUtils.isEmpty(id)) {
				throw new OAuthException("Authentication response is not sufficient");
			}

			PrintWriter out = resp.getWriter();
			resp.setContentType("text/html; charset=UTF-8");
			out.println("<html><head></head>"); //$NON-NLS-1$
			// TODO: send a message using
			// window.eclipseMessage.postImmediate(otherWindow, message) from
			// /org.eclipse.e4.webide/web/js/message.js
			out.println("<body onload=\"window.opener.handleOAuthResponse('" + id + "');window.close();\">"); //$NON-NLS-1$  //$NON-NLS-2$
			out.println("</body>"); //$NON-NLS-1$
			out.println("</html>"); //$NON-NLS-1$

			out.close();
			return;
		}
	}

	public static String getAuthType() {
		return OAUTH; //$NON-NLS-1$
	}

	public static IOrionCredentialsService getDefaultUserAdmin() {
		return userAdmin;
	}

	public void setUserAdmin(IOrionCredentialsService userAdmin) {
		OAuthHelper.userAdmin = userAdmin;
	}

	public void unsetUserAdmin(IOrionCredentialsService userAdmin) {
		if (userAdmin.equals(OAuthHelper.userAdmin)) {
			OAuthHelper.userAdmin = null;
		}
	}

	public static IOrionUserProfileService getUserProfileService() {
		return userProfileService;
	}

	public static void bindUserProfileService(IOrionUserProfileService _userProfileService) {
		userProfileService = _userProfileService;
	}

	public static void unbindUserProfileService(IOrionUserProfileService userProfileService) {
		userProfileService = null;
	}

	private static IEventService getEventService() {
		if (eventService == null) {
			BundleContext context = Activator.getBundleContext();
			ServiceReference<IEventService> eventServiceRef = context.getServiceReference(IEventService.class);
			if (eventServiceRef == null) {
				// Event service not available
				return null;
			}
			eventService = context.getService(eventServiceRef);
			if (eventService == null) {
				// Event service not available
				return null;
			}
		}
		return eventService;
	}

	/**
	 * Returns the string representation of the request  to use for the redirect (the URL
	 * to return to after oauth login completes). The returned URL will include the host and
	 * path, but no query parameters. If this server has not been configured with a different
	 * authentication host, then the server that received this request is considered to
	 * be the authentication server.
	 */
	static StringBuffer getAuthServerRequest(HttpServletRequest req) {
		//use authentication host for redirect because we may be sitting behind a proxy
		String hostPref = PreferenceHelper.getString(ServerConstants.CONFIG_AUTH_HOST, null);
		//assume non-proxy direct server connection if no auth host defined
		if (hostPref == null)
			return req.getRequestURL();
		StringBuffer result = new StringBuffer(hostPref);
		result.append(req.getServletPath());
		if (req.getPathInfo() != null)
			result.append(req.getPathInfo());

		Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.oauth"); //$NON-NLS-1$
		if (logger.isInfoEnabled())
			logger.info("Auth server redirect: " + result.toString()); //$NON-NLS-1$
		return result;
	}
}
