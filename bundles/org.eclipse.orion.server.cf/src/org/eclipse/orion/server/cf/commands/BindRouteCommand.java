/*******************************************************************************
 * Copyright (c) 2013 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.cf.commands;

import javax.servlet.http.HttpServletResponse;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.orion.server.cf.CFProtocolConstants;
import org.eclipse.orion.server.cf.manifest.ManifestUtils;
import org.eclipse.orion.server.cf.manifest.ParseException;
import org.eclipse.orion.server.cf.objects.App;
import org.eclipse.orion.server.cf.objects.Target;
import org.eclipse.orion.server.cf.utils.MultiServerStatus;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.osgi.util.NLS;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BindRouteCommand extends AbstractCFCommand {
	private final Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.cf"); //$NON-NLS-1$

	private String commandName;
	private App application;

	/* shared properties */
	private String appDomain;
	private String domainName;
	private JSONObject route;

	public JSONObject getRoute() {
		return route;
	}

	public String getAppDomain() {
		return appDomain;
	}

	public String getDomainName() {
		return domainName;
	}

	public BindRouteCommand(Target target, App app) {
		super(target);

		String[] bindings = {app.getName(), app.getGuid()};
		this.commandName = NLS.bind("Bind a new route to application {1} (guid: {2})", bindings);
		this.application = app;
	}

	@Override
	protected ServerStatus _doIt() {
		/* multi server status */
		MultiServerStatus status = new MultiServerStatus();

		try {

			/* get available domains */
			GetDomainsCommand getDomains = new GetDomainsCommand(target);
			ServerStatus jobStatus = (ServerStatus) getDomains.doIt(); /* FIXME: unsafe type cast */
			status.add(jobStatus);

			if (!jobStatus.isOK())
				return status;

			/* extract available domains */
			JSONObject domains = jobStatus.getJsonData();

			if (domains.getInt(CFProtocolConstants.V2_KEY_TOTAL_RESULTS) < 1) {
				status.add(new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, "Failed to find available domains in target", null));
				return status;
			}

			String domainGUID = null;
			if (!appDomain.isEmpty()) {
				/* look if the domain is available */
				int resources = domains.getJSONArray(CFProtocolConstants.V2_KEY_RESOURCES).length();
				for (int k = 0; k < resources; ++k) {
					JSONObject resource = domains.getJSONArray(CFProtocolConstants.V2_KEY_RESOURCES).getJSONObject(k);
					String tmpDomainName = resource.getJSONObject(CFProtocolConstants.V2_KEY_ENTITY).getString(CFProtocolConstants.V2_KEY_NAME);
					if (appDomain.equals(tmpDomainName)) {
						domainGUID = resource.getJSONObject(CFProtocolConstants.V2_KEY_METADATA).getString(CFProtocolConstants.V2_KEY_GUID);
						domainName = tmpDomainName;
						break;
					}
				}

				/* client requested an unavailable domain, fail */
				if (domainGUID == null) {
					String msg = NLS.bind("Failed to find domain {1} in target", appDomain);
					status.add(new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, msg, null));
					return status;
				}

			} else {
				/* client has not requested a specific domain, get the first available */
				JSONObject resource = domains.getJSONArray(CFProtocolConstants.V2_KEY_RESOURCES).getJSONObject(0);
				domainName = resource.getJSONObject(CFProtocolConstants.V2_KEY_ENTITY).getString(CFProtocolConstants.V2_KEY_NAME);
				domainGUID = resource.getJSONObject(CFProtocolConstants.V2_KEY_METADATA).getString(CFProtocolConstants.V2_KEY_GUID);
			}

			/* new application, we do not need to check for attached routes, create a new one */
			CreateRouteCommand createRoute = new CreateRouteCommand(target, application, domainGUID);
			jobStatus = (ServerStatus) createRoute.doIt(); /* FIXME: unsafe type cast */
			status.add(jobStatus);

			if (!jobStatus.isOK())
				return status;

			/* extract route guid */
			route = jobStatus.getJsonData();
			String routeGUID = route.getJSONObject(CFProtocolConstants.V2_KEY_METADATA).getString(CFProtocolConstants.V2_KEY_GUID);

			/* attach route to application */
			AttachRouteCommand attachRoute = new AttachRouteCommand(target, application, routeGUID);
			jobStatus = (ServerStatus) attachRoute.doIt(); /* FIXME: unsafe type cast */
			status.add(jobStatus);

			if (!jobStatus.isOK())
				return status;

			return status;

		} catch (Exception e) {
			String msg = NLS.bind("An error occured when performing operation {0}", commandName); //$NON-NLS-1$
			logger.error(msg, e);
			status.add(new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg, e));
			return status;
		}
	}

	@Override
	protected IStatus validateParams() {
		try {
			/* read deploy parameters */
			JSONObject appJSON = ManifestUtils.getApplication(application.getManifest());
			appDomain = appJSON.optString(CFProtocolConstants.V2_KEY_DOMAIN); /* optional */
			return Status.OK_STATUS;

		} catch (ParseException e) {
			return new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, e.getMessage(), null);
		}
	}
}
