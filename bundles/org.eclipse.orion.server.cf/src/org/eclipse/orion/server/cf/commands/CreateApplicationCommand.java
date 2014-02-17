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

import java.net.URI;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.eclipse.core.runtime.*;
import org.eclipse.orion.server.cf.CFProtocolConstants;
import org.eclipse.orion.server.cf.manifest.ManifestUtils;
import org.eclipse.orion.server.cf.manifest.ParseException;
import org.eclipse.orion.server.cf.objects.App;
import org.eclipse.orion.server.cf.objects.Target;
import org.eclipse.orion.server.cf.utils.HttpUtil;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.osgi.util.NLS;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateApplicationCommand extends AbstractCFCommand {
	private final Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.cf"); //$NON-NLS-1$

	private String commandName;
	private App application;

	/* shared parameters */
	private String appName;
	private String appCommand;
	private int appInstances;
	private int appMemory;

	private boolean force;

	public CreateApplicationCommand(Target target, App app, boolean force) {
		super(target);
		this.commandName = "Create a new application";
		this.application = app;
		this.force = force;
	}

	@Override
	protected ServerStatus _doIt() {
		try {

			if (force) {
				/* make sure we override the application */
				DeleteApplicationCommand deleteApplicationCommand = new DeleteApplicationCommand(target, application);
				deleteApplicationCommand.doIt(); /* we don't need to know whether the deletion succeeded or not */
			}

			/* create cloud foundry application */
			URI targetURI = URIUtil.toURI(target.getUrl());
			URI appsURI = targetURI.resolve("/v2/apps"); //$NON-NLS-1$

			PostMethod createAppMethod = new PostMethod(appsURI.toString());
			HttpUtil.configureHttpMethod(createAppMethod, target);

			/* set request body */
			JSONObject createAppRequst = new JSONObject();
			createAppRequst.put(CFProtocolConstants.V2_KEY_SPACE_GUID, target.getSpace().getCFJSON().getJSONObject(CFProtocolConstants.V2_KEY_METADATA).getString(CFProtocolConstants.V2_KEY_GUID));
			createAppRequst.put(CFProtocolConstants.V2_KEY_NAME, appName);
			createAppRequst.put(CFProtocolConstants.V2_KEY_INSTANCES, appInstances);
			createAppRequst.put(CFProtocolConstants.V2_KEY_BUILDPACK, JSONObject.NULL);
			createAppRequst.put(CFProtocolConstants.V2_KEY_COMMAND, appCommand);
			createAppRequst.put(CFProtocolConstants.V2_KEY_MEMORY, appMemory);
			createAppRequst.put(CFProtocolConstants.V2_KEY_STACK_GUID, JSONObject.NULL);
			createAppMethod.setRequestEntity(new StringRequestEntity(createAppRequst.toString(), "application/json", "utf-8")); //$NON-NLS-1$ //$NON-NLS-2$

			ServerStatus status = HttpUtil.executeMethod(createAppMethod);
			if (!status.isOK())
				return status;

			/* extract application guid */
			JSONObject appResp = status.getJsonData();
			application.setGuid(appResp.getJSONObject(CFProtocolConstants.V2_KEY_METADATA).getString(CFProtocolConstants.V2_KEY_GUID));
			application.setName(appName);

			return status;

		} catch (Exception e) {
			String msg = NLS.bind("An error occured when performing operation {0}", commandName); //$NON-NLS-1$
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

			appCommand = appJSON.optString(CFProtocolConstants.V2_KEY_COMMAND);
			appInstances = ManifestUtils.getInstances(appJSON.optString(CFProtocolConstants.V2_KEY_INSTANCES)); /* optional */
			appMemory = ManifestUtils.getMemoryLimit(appJSON.optString(CFProtocolConstants.V2_KEY_MEMORY)); /* optional */

			return Status.OK_STATUS;

		} catch (ParseException e) {
			return new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, e.getMessage(), null);
		}
	}
}
