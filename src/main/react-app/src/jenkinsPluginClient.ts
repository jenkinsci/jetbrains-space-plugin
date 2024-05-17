export const rootURL: string = (window as any).rootURL;

export const staticRootURL: string = `${rootURL}/plugin/jetbrains-space/react`

export async function fetchCrumb() {
    const response = await fetch(`${rootURL}/crumbIssuer/api/json`);
    return (await response.json() as JenkinsCrumb);
}

export async function fetchSpaceApp(connectionId: string): Promise<SpaceApp | string> {
    const response = await fetch(`${rootURL}/jb-space-projects/spaceApp?connectionId=${encodeURIComponent(connectionId)}`);
    return response.ok
        ? await response.json() as SpaceApp
        : await response.text();
}

export async function fetchProjectSpaceApp(jenkinsItemFullName: string): Promise<SpaceApp | string> {
    var url = `${rootURL}/jb-space-projects/projectSpaceApp?jenkinsItem=${encodeURIComponent(jenkinsItemFullName)}`;
    const response = await fetch(url);
    return response.ok
        ? await response.json() as SpaceApp
        : await response.text();
}

export async function fetchMultiBranchProjectSpaceApp(projectFullName: string, spaceConnectionId: string, spaceProjectKey: string): Promise<SpaceApp | string> {
    var url = `${rootURL}/jb-space-projects/multiBranchProjectSpaceApp` +
        `?jenkinsItem=${encodeURIComponent(projectFullName)}` +
        `&connectionId=${encodeURIComponent(spaceConnectionId)}` +
        `&projectKey=${encodeURIComponent(spaceProjectKey)}`;
    const response = await fetch(url);
    return response.ok
        ? await response.json() as SpaceApp
        : await response.text();
}

export async function fetchSpaceConnections(): Promise<SpaceConnection[]> {
    const crumbData = await fetchCrumb();
    const response = await fetch(`${rootURL}/jb-space-projects/spaceConnections`, {
        method: "GET",
        headers: {
            [crumbData.crumbRequestField]: crumbData.crumb
        },
    });
    return await response.json() as SpaceConnection[];
}

export async function exchangeSpaceCodeForToken(state: string, code: string): Promise<void> {
    const crumbData = await fetchCrumb();
    await fetch(`${rootURL}/jb-spacecode-oauth/exchangeCodeForToken?state=${state}&code=${code}`, {
        method: "POST",
        headers: {
            [crumbData.crumbRequestField]: crumbData.crumb
        },
    });
}

export async function fetchSpaceProjects(state: string): Promise<SpaceProject[]> {
    const crumbData = await fetchCrumb();
    const response = await fetch(`${rootURL}/jb-space-projects/fetchProjects?sessionId=${state}`, {
        method: "POST",
        headers: {
            [crumbData.crumbRequestField]: crumbData.crumb
        },
    });
    return await response.json();
}

export async function fetchSpaceRepositories(projectKey: string, sessionId: string): Promise<string[]> {
    const crumbData = await fetchCrumb();
    const response = await fetch(`${rootURL}/jb-space-projects/fetchRepositories?projectKey=${projectKey}&sessionId=${sessionId}`, {
        method: "POST",
        headers: {
            [crumbData.crumbRequestField]: crumbData.crumb
        },
    });
    return await response.json();
}

export async function connectToProject(jenkinsItem: string, connectionId: string, projectKey: string, repository?: string): Promise<void> {
    const crumbData = await fetchCrumb();
    const url = new URL(`${rootURL}/jb-space-projects/connectToProject`, window.location.origin);
    url.searchParams.append("jenkinsItem", jenkinsItem);
    url.searchParams.append("connectionId", connectionId);
    url.searchParams.append("projectKey", projectKey);
    if (repository) {
        url.searchParams.append("repository", repository);
    }
    await fetch(url.toString(), {
        method: "POST",
        headers: {
            [crumbData.crumbRequestField]: crumbData.crumb
        },
    });
}

export interface SpaceApp {
    appId: string,
    appName: string,
    managePermissionsUrl: string,
    permissions: string[],
    missingPermissions: string[]
}

export interface SpaceConnection {
    id: string;
    baseUrl: string;
}

export interface SpaceProject {
    key: string;
    name: string;
    iconUrl: string;
}

export interface JenkinsCrumb {
    crumb: string;
    crumbRequestField: string;
}
