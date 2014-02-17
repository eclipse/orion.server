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
package org.eclipse.orion.server.cf.commands;

import java.net.URI;
import java.util.ArrayList;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.eclipse.core.runtime.*;
import org.eclipse.orion.server.cf.CFProtocolConstants;
import org.eclipse.orion.server.cf.manifest.ManifestUtils;
import org.eclipse.orion.server.cf.manifest.ParseException;
import org.eclipse.orion.server.cf.objects.App;
import org.eclipse.orion.server.cf.objects.Target;
import org.eclipse.orion.server.cf.utils.HttpUtil;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.osgi.util.NLS;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteApplicationCommand extends AbstractCFCommand {
	private final Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.cf"); //$NON-NLS-1$

	private String commandName;
	private App application;

	private String appName;

	public DeleteApplicationCommand(Target target, App app) {
		super(target);
		this.commandName = "Delete the application";
		this.application = app;
	}

	@Override
	protected ServerStatus _doIt() {
		try {

			/* read deploy parameters */
			JSONObject appMetadata = null;
			JSONObject appEntity = null;

			URI targetURI = URIUtil.toURI(target.getUrl());

			/* get application details */
			String appsUrl = target.getSpace().getCFJSON().getJSONObject("entity").getString("apps_url"); //$NON-NLS-1$//$NON-NLS-2$
			URI appsURI = targetURI.resolve(appsUrl);
			GetMethod getAppsMethod = new GetMethod(appsURI.toString());
			HttpUtil.configureHttpMethod(getAppsMethod, target);
			getAppsMethod.setQueryString("q=name:" + appName + "&inline-relations-depth=1"); //$NON-NLS-1$ //$NON-NLS-2$

			ServerStatus appsStatus = HttpUtil.executeMethod(getAppsMethod);
			if (!appsStatus.isOK())
				return appsStatus;

			JSONObject apps = appsStatus.getJsonData();
			if (!apps.has("resources") || apps.getJSONArray("resources").length() == 0) //$NON-NLS-1$//$NON-NLS-2$
				return new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_NOT_FOUND, "Application not found", null);

			appMetadata = apps.getJSONArray("resources").getJSONObject(0).getJSONObject("metadata"); //$NON-NLS-1$ //$NON-NLS-2$
			appEntity = apps.getJSONArray("resources").getJSONObject(0).getJSONObject("entity"); //$NON-NLS-1$ //$NON-NLS-2$

			if (application.getGuid() == null) {

				String summaryAppUrl = appMetadata.getString("url") + "/summary"; //$NON-NLS-1$ //$NON-NLS-2$
				URI summaryAppURI = targetURI.resolve(summaryAppUrl);

				GetMethod getSummaryMethod = new GetMethod(summaryAppURI.toString());
				HttpUtil.configureHttpMethod(getSummaryMethod, target);

				ServerStatus getStatus = HttpUtil.executeMethod(getSummaryMethod);
				if (!getStatus.isOK())
					return getStatus;

				JSONObject summaryJSON = getStatus.getJsonData();

				/* set known application GUID */
				application.setGuid(summaryJSON.getString(CFProtocolConstants.V2_KEY_GUID));
			}

			/* gather application service bindings */
			ArrayList<String> serviceInstances = new ArrayList<String>();
			JSONArray appServiceBindings = appEntity.getJSONArray("service_bindings"); //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
			for (int i = 0; i < appServiceBindings.length(); ++i) {
				JSONObject binding = appServiceBindings.getJSONObject(i).getJSONObject("entity"); //$NON-NLS-1$
				serviceInstances.add(binding.getString("service_instance_url")); //$NON-NLS-1$
			}

			/* delete the application */
			URI appURI = targetURI.resolve("/v2/apps/" + application.getGuid()); //$NON-NLS-1$

			DeleteMethod deleteAppMethod = new DeleteMethod(appURI.toString());
			HttpUtil.configureHttpMethod(deleteAppMethod, target);
			deleteAppMethod.setQueryString("recursive=true"); //$NON-NLS-1$

			ServerStatus status = HttpUtil.executeMethod(deleteAppMethod);
			if (!status.isOK())
				return status;

			/* forcefully delete the gathered service instances */
			for (String serviceInstanceURL : serviceInstances) {
				URI serviceInstanceURI = targetURI.resolve(serviceInstanceURL);
				DeleteMethod deleteServiceInstanceMethod = new DeleteMethod(serviceInstanceURI.toString());
				HttpUtil.configureHttpMethod(deleteServiceInstanceMethod, target);

				ServerStatus serviceStatus = HttpUtil.executeMethod(deleteServiceInstanceMethod);
				if (!serviceStatus.isOK())
					return serviceStatus;
			}

			return status;

		} catch (Exception e) {
			String msg = NLS.bind("An error occured when performing operation {0}", commandName);
			logger.error(msg, e);
			return new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg, e);
		}
	}

	@Override
	protected IStatus validateParams() {
		try {
			/* read deploy parameters */
			JSONObject appJSON = ManifestUtils.getApplication(application.getManifest());
			appName = ManifestUtils.getApplicationName(appJSON); /* required */
			return Status.OK_STATUS;

		} catch (ParseException e) {
			return new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, e.getMessage(), null);
		}
	}
}
