/*******************************************************************************
 * Copyright (c) 2010, 2014 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.servlets.useradmin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.orion.internal.server.servlets.ServletResourceHandler;
import org.eclipse.orion.internal.server.servlets.workspace.authorization.AuthorizationService;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.OrionConfiguration;
import org.eclipse.orion.server.core.PreferenceHelper;
import org.eclipse.orion.server.core.ServerConstants;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.orion.server.core.metastore.UserInfo;
import org.eclipse.orion.server.core.users.UserConstants2;
import org.eclipse.orion.server.servlets.OrionServlet;
import org.eclipse.orion.server.useradmin.UserConstants;
import org.eclipse.osgi.util.NLS;

// POST /users/ creates a new user
// GET /users/ gets list of users
//
// One user methods:
//
// GET /users/[userId] gets user details
// PUT /users/[userId] updates user details
// DELETE /users/[usersId] deletes a user
public class UserServlet extends OrionServlet {

	private static final long serialVersionUID = -6809742538472682623L;

	private List<String> authorizedAccountCreators;
	private ServletResourceHandler<String> userSerializer;

	@Override
	public void init() throws ServletException {
		userSerializer = new ServletUserHandler(getStatusHandler());
		String creators = PreferenceHelper.getString(ServerConstants.CONFIG_AUTH_USER_CREATION, null);
		if (creators != null) {
			authorizedAccountCreators = new ArrayList<String>();
			authorizedAccountCreators.addAll(Arrays.asList(creators.split(","))); //$NON-NLS-1$
		}
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String login = req.getRemoteUser();
		if ("POST".equals(req.getMethod())) { //$NON-NLS-1$
			if (req.getParameter(UserConstants.KEY_RESET) == null) {
				// either everyone can create users, or only the specific list
				if (authorizedAccountCreators != null && !authorizedAccountCreators.contains(login)) {
					resp.sendError(HttpServletResponse.SC_FORBIDDEN);
					return;
				}
			}
		} else {
			if (login == null) {
				resp.sendError(HttpServletResponse.SC_FORBIDDEN);
				return;
			}

			try {
				String requestPath = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
				if (!AuthorizationService.checkRights(login, requestPath, req.getMethod())) {
					resp.sendError(HttpServletResponse.SC_FORBIDDEN);
					return;
				}
			} catch (CoreException e) {
				resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				return;
			}
		}

		traceRequest(req);
		String pathInfo = req.getPathInfo();

		if (pathInfo != null && !pathInfo.equals("/")) {
			String userId = pathInfo.split("\\/")[1];
			UserInfo userInfo = null;
			try {
				userInfo = OrionConfiguration.getMetaStore().readUserByProperty(UserConstants2.USER_NAME, userId, false, false);
			} catch (CoreException e) {
				LogHelper.log(e);
				resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
				return;
			}

			if (userInfo == null) {
				handleException(resp, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_NOT_FOUND, NLS.bind("User not found: {0}", userId), null));
				return;
			}
		}

		if (userSerializer.handleRequest(req, resp, pathInfo))
			return;
		// finally invoke super to return an error for requests we don't know how to handle
		super.service(req, resp);
	}
}
