import React, {FC, useCallback, useEffect, useState} from "react";
import {ConnectionSelect, ConnectToSpaceProjectBlock, getCurrentItemFullPathFromURL} from "./SpaceProjectConnection";
import Select from "react-select";
import {connectToProject, fetchMultiBranchProjectSpaceApp, fetchSpaceRepositories, SpaceProject} from "./jenkinsPluginClient";
import {SpaceAppInfoComponent} from "./SpaceAppInfoComponent";

export interface SpaceRepoConnectionProps {
    connectionIdInput: HTMLInputElement;
    projectKeyInput: HTMLInputElement;
    repositoryInput: HTMLInputElement;
}

export const SpaceRepoConnection: FC<SpaceRepoConnectionProps> = (inputs) => {
    const [connectionId, setConnectionId] = useState<string | null>(inputs.connectionIdInput.value);
    const [spaceUserTokenId, setSpaceUserTokenId] = useState<string | null>(null);
    const [projectKey, setProjectKey] = useState<string | null>(inputs.projectKeyInput.value);
    const [repository, setRepository] = useState<string | null>(inputs.repositoryInput.value);
    const [connecting, setConnecting] = useState(false);

    const jenkinsItemFullName = getCurrentItemFullPathFromURL()
    const onConnectClick = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        if (connectionId && projectKey && repository && jenkinsItemFullName) {
            setConnecting(true);
            connectToProject(jenkinsItemFullName, connectionId, projectKey, repository).then(() => {
                setSpaceUserTokenId(null);
                setConnecting(false);
            });
        }
    }, [connectionId, projectKey, repository, jenkinsItemFullName]);

    if (jenkinsItemFullName == null) {
        console.error("Repository connection component can only be used on the multibranch project configuration page");
        return <></>;
    }

    return <div className="flex flex-col">
        <div className="jenkins-form-item tr">
            <div className="jenkins-form-label help-sibling">Connection</div>
            {connectionId && projectKey && repository && !spaceUserTokenId
                ? <input type="text" readOnly value={connectionId} className="jenkins-input"/>
                : <ConnectionSelect
                    value={connectionId}
                    onChange={(c) => {
                        if (c?.id !== connectionId) {
                            setConnectionId(c?.id || null);
                            setProjectKey(null);
                            setRepository(null);
                        }
                    }}/>
            }
        </div>

        {connectionId &&
            <div className="jenkins-form-item tr">
                <ProjectSelect
                    connectionId={connectionId}
                    value={projectKey}
                    onChange={(value) => {
                        if (value !== projectKey) {
                            setProjectKey(value);
                            setRepository(null);
                        }
                    }}
                    onSpaceTokenObtained={setSpaceUserTokenId}
                />
            </div>
        }

        {connectionId && projectKey &&
            <div className="jenkins-form-item tr">
                <div className="jenkins-form-label help-sibling">Repository</div>
                {spaceUserTokenId
                    ? <RepoSelect
                        projectKey={projectKey}
                        value={repository}
                        spaceUserTokenId={spaceUserTokenId}
                        onChange={(value) => {
                            setRepository(value);
                            inputs.connectionIdInput.value = connectionId;
                            inputs.projectKeyInput.value = projectKey;
                            inputs.repositoryInput.value = value;
                        }}/>
                    : repository && <input type="text" readOnly value={repository} className="jenkins-input"/>
                }

                {repository && spaceUserTokenId &&
                    (connecting
                        ? <p className="jenkins-spinner mt-1">
                            Connecting JetBrains SpaceCode project...
                        </p>
                        : <button className="jenkins-button jenkins-button--primary mt-4" onClick={onConnectClick}>
                            Connect project to JetBrains SpaceCode
                        </button>)
                }

                {connectionId && projectKey && repository && !spaceUserTokenId &&
                    <SpaceAppInfoComponent
                        id={`${jenkinsItemFullName}|${connectionId}|${projectKey}`}
                        fetchSpaceApp={() => fetchMultiBranchProjectSpaceApp(jenkinsItemFullName, connectionId, projectKey)}
                        showOnlyErrors={true}/>
                }
            </div>
        }

    </div>;
}

const ProjectSelect: FC<{
    connectionId: string,
    value: string | null,
    onChange: (value: string) => void,
    onSpaceTokenObtained: (tokenId: string) => void
}> = ({connectionId, value, onSpaceTokenObtained, onChange}) => {
    return (value == null)
        ? <ConnectToSpaceProjectBlock
            connectionId={connectionId}
            projectKey={value}
            withVcsReadScope={true}
            onSpaceTokenObtained={onSpaceTokenObtained}
            renderProjectsList={(projects) =>
                <SpaceProjectsList projects={projects} onChange={onChange} />
            }
        />

        : <>
            <div className="jenkins-form-label help-sibling">Project</div>
            <input type="text" readOnly value={value} className="jenkins-input"/>
        </>;
}

const SpaceProjectsList: FC<{
    projects: SpaceProject[] | null,
    onChange: (newValue: string) => void
}> = ({projects, onChange}) => {
    const [project, setProject] = useState<SpaceProject | null>(null);
    return <div className="relative w-full flex flex-col gap-2 mt-2">
        <Select
            options={projects || []}
            isDisabled={projects === null}
            isLoading={projects === null}
            value={project}
            onChange={(value) => {
                setProject(value);
                if (value) {
                    onChange(value.key);
                }
            }}
            getOptionValue={(p) => p.key}
            getOptionLabel={(p) => `${p.name} (${p.key})`}
            isSearchable={true}
            placeholder="Choose SpaceCode project"
            className="w-full"
        />
    </div>
}

const RepoSelect: FC<{
    projectKey: string,
    spaceUserTokenId: string,
    value: string | null,
    onChange: (value: string) => void
}> = ({ projectKey, spaceUserTokenId, value, onChange }) => {
    const [repositories, setRepositories] = useState<SpaceRepository[] | null>(null);
    const fetchRepositories = useCallback(async () => {
        const repos = await fetchSpaceRepositories(projectKey, spaceUserTokenId);
        setRepositories(repos.map((name) => ({ name })));
    }, [projectKey, spaceUserTokenId]);

    useEffect(() => {
        fetchRepositories()
    }, [fetchRepositories]);

    return <div className="relative w-full flex flex-col gap-2 mt-2">
        <Select
            options={repositories || []}
            isDisabled={repositories === null}
            isLoading={repositories === null}
            getOptionValue={(repo) => repo.name}
            getOptionLabel={(repo) => repo.name}
            value={value ? { name: value } : null}
            onChange={(newValue) => {
                if (newValue) onChange(newValue.name)
            }}
            isSearchable={true}
            placeholder="Choose SpaceCode repository"
            className="w-full"
        />
    </div>
}

interface SpaceRepository { name: string }