import React, {FC, useCallback, useEffect, useMemo, useState} from "react";
import Select from "react-select";
import {
    connectToProject, exchangeSpaceCodeForToken,
    fetchSpaceConnections, fetchProjectSpaceApp,
    fetchSpaceProjects,
    rootURL,
    SpaceConnection,
    SpaceProject
} from "./jenkinsPluginClient";
import {resetProjectAppState, SpaceAppInfoComponent} from "./SpaceAppInfoComponent";

export interface SpaceProjectConnectionProps {
    connectionIdInput: HTMLInputElement;
    projectKeyInput: HTMLInputElement;
}

export const SpaceProjectConnection: FC<SpaceProjectConnectionProps> = (inputs) => {
    const [connectionId, setConnectionId] = useState<string | null>(inputs.connectionIdInput.value);
    const [projectKey, setProjectKey] = useState<string | null>(inputs.projectKeyInput.value);

    const jenkinsJob = getCurrentItemFullPathFromURL()
    if (jenkinsJob == null) {
        console.error("Project connection component can only be used on the job configuration page");
        return <></>;
    }

    return <div className="flex flex-col">
        <div className="jenkins-form-item tr">
            <div className="jenkins-form-label help-sibling">Connection</div>
            <ConnectionSelect
                value={connectionId}
                onChange={(c) => {
                    if (c?.id !== connectionId) {
                        setConnectionId(c?.id || null);
                        setProjectKey(null);
                    }
                }}/>
        </div>

        {connectionId &&
            <div className="jenkins-form-item tr">
                <ProjectSelect
                    connectionId={connectionId}
                    value={projectKey}
                    onChange={(value) => {
                        setProjectKey(value);
                        resetProjectAppState(jenkinsJob);
                        inputs.connectionIdInput.value = connectionId;
                        inputs.projectKeyInput.value = value;
                    }}/>
            </div>
        }
    </div>;
}

export const ConnectionSelect: FC<{ value: string | null, onChange: (value: SpaceConnection | null) => void }> = ({ value, onChange }) => {
    const [connections, setConnections] = useState<SpaceConnection[] | null>(null);

    // do not depend on value change, fetch should only be done on first load
    /* eslint-disable react-hooks/exhaustive-deps */
    useEffect(() => {
        fetchSpaceConnections().then((result) => {
            setConnections(result);
            onChange((result || []).filter((c) => c.id === value)[0] || null);
        });
    }, []);
    /* eslint-enable react-hooks/exhaustive-deps */

    const connection = useMemo(
        () => (connections || []).filter((c) => c.id === value)[0],
        [connections, value])

    return <Select
        placeholder="Choose SpaceCode instance"
        options={connections || []}
        value={connection}
        isDisabled={connections === null}
        isLoading={connections === null}
        onChange={onChange}
        getOptionValue={(c) => c.id}
        getOptionLabel={(c) => c.id}
        className="jb-space-select"
    />
}

const ProjectSelect: FC<{
    connectionId: string,
    value: string | null,
    onChange: (value: string) => void
}> = ({connectionId, value, onChange}) => {
    const [selectProjectBlockVisible, setSelectProjectBlockVisible] = useState(false);
    const showSelectProjectBlock = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        setSelectProjectBlockVisible(true);
    }, []);
    const jobFullName = getCurrentItemFullPathFromURL()


    const onConnect = useCallback((project: SpaceProject) => {
        setSelectProjectBlockVisible(false);
        if (jobFullName) {
            connectToProject(jobFullName, connectionId, project.key).then(() => {
                onChange(project.key);
            });
        }
    }, [connectionId, jobFullName, onChange]);
    const onCancel = useCallback(() => setSelectProjectBlockVisible(false), []);

    if (jobFullName == null) {
        console.error("Project connection component can only be used on the job configuration page");
        return <></>;
    }

    return (selectProjectBlockVisible || value == null)
        ? <ConnectToSpaceProjectBlock
            connectionId={connectionId}
            projectKey={value}
            withVcsReadScope={false}
            onCancel={onCancel}
            renderProjectsList={(projects) =>
                <SpaceProjectsList projects={projects} onConnect={onConnect} onCancel={onCancel}/>
            }
        />

        : <>
            <div className="jenkins-form-label help-sibling">Project</div>
            <div className="flex flex-row gap-1">
                <input type="text" readOnly value={value} className="jenkins-input" />
                <button className="jenkins-button" onClick={showSelectProjectBlock}>
                    <img src={`${rootURL}/plugin/jetbrains-space/icons/space.svg`} alt="" width="24" height="24"/>
                    Choose another project
                </button>
            </div>

            <SpaceAppInfoComponent
                id={jobFullName}
                fetchSpaceApp={() => fetchProjectSpaceApp(jobFullName)}
                showOnlyErrors={true} />
        </>;
}

export const ConnectToSpaceProjectBlock: FC<{
    connectionId: string,
    projectKey: string | null,
    withVcsReadScope: boolean,
    onSpaceTokenObtained?: (tokenId: string) => void,
    onCancel?: () => void,
    renderProjectsList: (projects: SpaceProject[] | null) => JSX.Element
}> =
    ({connectionId, projectKey, withVcsReadScope, onSpaceTokenObtained, onCancel, renderProjectsList}) => {
        const [projects, setProjects] = useState<SpaceProject[] | null>(null);
        const [showProjectsLoader, setShowProjectsLoader] = useState(false);

        const fetchProjects = useCallback(async (params: URLSearchParams) => {
            const state = params.get("state");
            const code = params.get("code");
            if (state && code) {
                await exchangeSpaceCodeForToken(state, code);
                setProjects(await fetchSpaceProjects(state));
                if (onSpaceTokenObtained)
                    onSpaceTokenObtained(state);
            }
            setShowProjectsLoader(false);
        }, [onSpaceTokenObtained]);

        const onStorageEvent = useCallback((event: StorageEvent) => {
            if (event.key === 'space-oauth-response') {
                fetchProjects(new URLSearchParams(event.newValue || ""));
                window.removeEventListener('storage', onStorageEvent);
            }
        }, [fetchProjects]);

        const onSignInClick = useCallback((event: React.MouseEvent) => {
            event.preventDefault();
            setShowProjectsLoader(true);
            let left = ((window.parent || window).screen.width - 500) / 2;
            let top = ((window.parent || window).screen.height - 300) / 2;
            var popup = window.open(
                `${rootURL}/jb-spacecode-oauth/obtainSpaceUserToken?connectionId=${connectionId}${withVcsReadScope ? "&withVcsReadScope=true" : ""}`,
                "_blank",
                "toolbar=no,scrollbars=yes,resizable=yes,width=500,height=600,top=" + top + ",left=" + left
            );
            if (!popup) {
                alert("Please disable your pop-up blocker and try again.");
                return;
            }
            popup.focus();
            window.addEventListener('storage', onStorageEvent);
        }, [connectionId, withVcsReadScope, onStorageEvent]);

        const onCancelClick = useCallback((event: React.MouseEvent) => {
            event.preventDefault();
            setShowProjectsLoader(false);
            if (onCancel)
                onCancel();
        }, [onCancel]);

        return (projects !== null || showProjectsLoader)
            ? renderProjectsList(projects)
            : <div className="flex flex-col gap-1 mt-1">
                <div>To show available projects Jenkins requires access to your SpaceCode account.</div>
                <div className="flex flex-row gap-2 mt-2">
                    <button className="jenkins-button jenkins-button--primary" onClick={onSignInClick}>Sign in to
                        SpaceCode
                    </button>

                    {projectKey && <button className="jenkins-button" onClick={onCancelClick}>Cancel</button>}
                </div>
            </div>
    }

const SpaceProjectsList: FC<{
    projects: SpaceProject[] | null,
    onConnect: (project: SpaceProject) => void,
    onCancel: () => void
}> =
    ({ projects, onConnect, onCancel }) => {
        const [project, setProject] = useState<SpaceProject | null>(null)
        const [connecting, setConnecting] = useState(false);

        const onConnectClick = useCallback((event: React.MouseEvent) => {
            event.preventDefault();
            if (project) {
                onConnect(project);
                setConnecting(true);
            }
        }, [project, onConnect]);
        const onCancelClick = useCallback((event: React.MouseEvent) => {
            event.preventDefault();
            onCancel();
        }, [onCancel]);

        return <>
            <div className="relative w-full flex flex-col gap-2 mt-2">
                <Select
                    options={projects || []}
                    isDisabled={projects === null}
                    isLoading={projects === null}
                    value={project}
                    onChange={(value) => setProject(value)}
                    getOptionValue={(p) => p.key}
                    getOptionLabel={(p) => `${p.name} (${p.key})`}
                    isSearchable={true}
                    placeholder="Choose SpaceCode project"
                    className="w-full"
                />

                {connecting
                    ? <p className="jenkins-spinner mt-1">
                        Connecting JetBrains SpaceCode project...
                    </p>
                    : <div className="flex flex-row gap-2 mt-2">
                        <button className="jenkins-button jenkins-button--primary" disabled={!project}
                                onClick={onConnectClick}>
                            Connect to project
                        </button>

                        <button className="jenkins-button" onClick={onCancelClick}>Cancel</button>
                    </div>
                }

            </div>
        </>
    }

export function getCurrentItemFullPathFromURL() {
    const urlParts = window.location.pathname.split('/');
    const jobParts = [];
    const jobSegment = 'job';

    // Iterate through the URL parts and collect all segments following any 'job' occurrence
    for (let i = 0; i < urlParts.length; i++) {
        if (urlParts[i] === jobSegment && i + 1 < urlParts.length) {
            jobParts.push(decodeURIComponent(urlParts[i + 1]));
            i++; // Skip the next part as it's already processed
        }
    }

    // Join all parts with '/' to form the full job path
    return jobParts.length > 0 ? jobParts.join('/') : null;
}
