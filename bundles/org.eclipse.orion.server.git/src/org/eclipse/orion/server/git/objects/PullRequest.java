/*******************************************************************************
 * Copyright (c) 2014, 2015 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.git.objects;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.orion.server.core.ProtocolConstants;
import org.eclipse.orion.server.core.resources.Property;
import org.eclipse.orion.server.core.resources.ResourceShape;
import org.eclipse.orion.server.core.resources.annotations.PropertyDescription;
import org.eclipse.orion.server.core.resources.annotations.ResourceDescription;
import org.eclipse.orion.server.git.GitConstants;
import org.eclipse.orion.server.git.servlets.GitServlet;
import org.json.JSONException;
import org.json.JSONObject;

@ResourceDescription(type = PullRequest.TYPE)
public class PullRequest extends GitObject {

	public static final String RESOURCE = "pullRequest"; //$NON-NLS-1$
	public static final String TYPE = "PullRequest"; //$NON-NLS-1$

	private JSONObject pullRequest;
	
	private static final ResourceShape DEFAULT_RESOURCE_SHAPE = new ResourceShape();
	{
		Property[] defaultProperties = new Property[] { //
			new Property(GitConstants.KEY_CLONE), //
			new Property(GitConstants.KEY_REMOTE), //
			new Property(GitConstants.KEY_PULL_REQUEST), //
			new Property(ProtocolConstants.KEY_TYPE) };
		DEFAULT_RESOURCE_SHAPE.setProperties(defaultProperties);
	}


	public PullRequest(URI cloneLocation, Repository db, JSONObject pullRequest) throws JSONException {
		super(cloneLocation, db);
		this.pullRequest = pullRequest;
	}

	@PropertyDescription(name = GitConstants.KEY_PULL_REQUEST)
	public JSONObject getPullRequest() {
		return this.pullRequest;
	}
	
	@PropertyDescription(name = GitConstants.KEY_CLONE)
	public URI getCloneLocation() {
		return this.cloneLocation;
	}

	@PropertyDescription(name = GitConstants.KEY_REMOTE)
	public URI getRemoteLocation() throws URISyntaxException {
		return createLocation(Remote.RESOURCE,cloneLocation);
	}
	
	@PropertyDescription(name = ProtocolConstants.KEY_TYPE)
	public String getType() throws URISyntaxException {
		return TYPE;
	}
	
	private URI createLocation( String resource, URI cloneLocation) throws URISyntaxException {
		IPath basePath = new Path(cloneLocation.getPath());
		IPath newPath = new Path(GitServlet.GIT_URI).append(resource).append(basePath.removeFirstSegments(2));
		return new URI(cloneLocation.getScheme(), cloneLocation.getUserInfo(), cloneLocation.getHost(), cloneLocation.getPort(), newPath.toString(),
				cloneLocation.getQuery(), cloneLocation.getFragment());
	}
	
	@Override
	public JSONObject toJSON() throws JSONException, URISyntaxException, IOException, CoreException {
		/* extend default commit properties */
		return jsonSerializer.serialize(this, DEFAULT_RESOURCE_SHAPE);
	}

	@Override
	protected URI getLocation() throws URISyntaxException {
		return null;
	}
}
