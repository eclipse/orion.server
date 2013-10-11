/*******************************************************************************
 * Copyright (c) 2011, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.git.jobs;

import java.io.*;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.core.runtime.*;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.orion.server.core.metastore.ProjectInfo;
import org.eclipse.orion.server.git.GitActivator;
import org.eclipse.orion.server.git.GitCredentialsProvider;
import org.eclipse.orion.server.git.objects.Clone;
import org.eclipse.orion.server.git.servlets.GitCloneHandlerV1;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A job to perform a clone operation in the background
 */
public class CloneJob extends GitJob {

	private final ProjectInfo project;
	private final Clone clone;
	private final String user;
	private final String gitUserName;
	private final String gitUserMail;
	private String cloneLocation;
	private final boolean initProject;

	public CloneJob(Clone clone, String userRunningTask, CredentialsProvider credentials, String user, String cloneLocation, ProjectInfo project, String gitUserName, String gitUserMail, boolean initProject) {
		super(userRunningTask, true, (GitCredentialsProvider) credentials);
		this.clone = clone;
		this.user = user;
		this.project = project;
		this.gitUserName = gitUserName;
		this.gitUserMail = gitUserMail;
		this.cloneLocation = cloneLocation;
		this.initProject = initProject;
		setFinalMessage("Clone complete.");
		setTaskExpirationTime(TimeUnit.DAYS.toMillis(7));
	}

	public CloneJob(Clone clone, String userRunningTask, CredentialsProvider credentials, String user, String cloneLocation, ProjectInfo project, String gitUserName, String gitUserMail) {
		this(clone, userRunningTask, credentials, user, cloneLocation, project, gitUserName, gitUserMail, false);
	}

	private IStatus doClone() {
		try {
			File cloneFolder = new File(clone.getContentLocation().getPath());
			if (!cloneFolder.exists()) {
				cloneFolder.mkdir();
			}
			CloneCommand cc = Git.cloneRepository();
			cc.setBare(false);
			cc.setCredentialsProvider(credentials);
			cc.setDirectory(cloneFolder);
			cc.setRemote(Constants.DEFAULT_REMOTE_NAME);
			cc.setURI(clone.getUrl());
			Git git = cc.call();

			// Configure the clone, see Bug 337820
			GitCloneHandlerV1.doConfigureClone(git, user, gitUserName, gitUserMail);
			git.getRepository().close();

			if (initProject) {
				File projectJsonFile = new File(cloneFolder.getPath() + File.separator + "project.json");
				if (!projectJsonFile.exists()) {
					PrintStream out = null;
					try {
						out = new PrintStream(new FileOutputStream(projectJsonFile));
						JSONObject projectjson = new JSONObject();

						String gitPath = clone.getUrl();
						if (gitPath.indexOf("://") > 0) {
							gitPath = gitPath.substring(gitPath.indexOf("://") + 3);
						}
						String[] segments = gitPath.split("/");
						String serverName = segments[0];
						if (serverName.indexOf("@") > 0) {
							serverName = serverName.substring(serverName.indexOf("@") + 1);
						}
						String repoName = segments[segments.length - 1];
						if (repoName.indexOf(".git") > 0) {
							repoName = repoName.substring(0, repoName.lastIndexOf(".git"));
						}
						projectjson.put("Name", repoName + " at " + serverName);
						out.print(projectjson.toString());
					} finally {
						if (out != null)
							out.close();
					}
				}
			}

		} catch (IOException e) {
			return new Status(IStatus.ERROR, GitActivator.PI_GIT, "Error cloning git repository", e);
		} catch (CoreException e) {
			return e.getStatus();
		} catch (GitAPIException e) {
			return getGitAPIExceptionStatus(e, "Error cloning git repository");
		} catch (JGitInternalException e) {
			return getJGitInternalExceptionStatus(e, "Error cloning git repository");
		} catch (Exception e) {
			return new Status(IStatus.ERROR, GitActivator.PI_GIT, "Error cloning git repository", e);
		}
		JSONObject jsonData = new JSONObject();
		try {
			jsonData.put(ProtocolConstants.KEY_LOCATION, URI.create(this.cloneLocation));
		} catch (JSONException e) {
			// Should not happen
		}

		return new ServerStatus(Status.OK_STATUS, HttpServletResponse.SC_OK, jsonData);
	}

	@Override
	protected IStatus performJob() {
		IStatus result = doClone();
		if (result.isOK())
			return result;
		try {
			if (project != null)
				GitCloneHandlerV1.removeProject(user, project);
			else
				FileUtils.delete(URIUtil.toFile(clone.getContentLocation()), FileUtils.RECURSIVE);
		} catch (IOException e) {
			//log the secondary failure and return the original failure
			String msg = "An error occurred when cleaning up after a clone failure"; //$NON-NLS-1$
			LogHelper.log(new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg, e));
		}
		return result;
	}
}
