import React, {FC, useCallback, useEffect, useState} from "react";
import {rootURL, SpaceApp} from "./jenkinsPluginClient";
import {create as createState, StoreApi, UseBoundStore} from "zustand";

export const SpaceAppInfoComponent: FC<{
    id: string,
    fetchSpaceApp: () => Promise<SpaceApp | string>,
    showOnlyErrors: boolean,
    onProblemsFixed?: () => void
}> = ({ id, fetchSpaceApp, showOnlyErrors, onProblemsFixed }) => {

    const useSpaceAppState = getSpaceAppState(id, fetchSpaceApp);
    const spaceApp = useSpaceAppState((state) => state.app);
    const isLoading = useSpaceAppState((state) => state.isLoading);
    const spaceRequestError = useSpaceAppState((state) => state.spaceRequestError);
    const refresh = useSpaceAppState((state) => state.refresh);

    const [initialLoad, setInitialLoad] = useState(true)

    // this effect should only run whenever spaceApp state is updated
    /* eslint-disable react-hooks/exhaustive-deps */
    useEffect(() => {
        if (spaceApp?.missingPermissions.length === 0 && !initialLoad && onProblemsFixed) {
            onProblemsFixed();
        }
        if (!isLoading) {
            setInitialLoad(false);
        }
    }, [spaceApp]);
    /* eslint-enable react-hooks/exhaustive-deps */

    const onRefreshButtonClick = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        refresh();
    }, [refresh]);

    const navigateToSpaceApp = useCallback(
        (e: React.MouseEvent) => {
            e.preventDefault();
            spaceApp && window.open(spaceApp.managePermissionsUrl, "_blank");
        },
        [spaceApp]);

    return isLoading
        ? ((showOnlyErrors && initialLoad)
            ? <></>
            : <p className="jenkins-spinner mt-2">Checking connection with JetBrains SpaceCode...</p>)

        : ((spaceApp != null)
                ? <>
                    {!showOnlyErrors &&
                        <div className="flex flex-row gap-1">
                            <span className="jenkins-form-label">Jenkins connection name in SpaceCode:</span>
                            <span>{spaceApp.appName}</span>
                        </div>
                    }

                    {!showOnlyErrors && spaceApp.permissions.length > 0 &&
                        <div className="flex flex-col">
                            <span className="jenkins-form-label mb-0">Permissions granted:</span>
                            <ul className="list-disc list-inside">
                                {spaceApp.permissions.map((p) => <li key={p}>{p}</li>)}
                            </ul>
                            <a href={spaceApp.managePermissionsUrl} target="_blank" rel="noreferrer" className="text-xs mt-2">Manage</a>
                        </div>
                    }

                    {spaceApp.missingPermissions.length > 0 &&
                        <div className="flex flex-col mt-2 alert alert-warning">
                            <span className="jenkins-form-label mb-0">Permissions missing or not approved:</span>
                            <ul className="list-disc list-inside">
                                {spaceApp.missingPermissions.map((p) => <li key={p}>{p}</li>)}
                            </ul>
                            <div className="flex flex-row gap-2 mt-2">
                                <button className="jenkins-button jenkins-button--primary mt-2 w-fit"
                                        onClick={navigateToSpaceApp}>
                                    Grant missing permissions
                                </button>
                                <button className="jenkins-button" onClick={onRefreshButtonClick}>
                                    <img src={`${rootURL}/plugin/jetbrains-space/icons/reload.svg`} alt="" width="16" height="16"/>
                                    Refresh
                                </button>
                            </div>
                        </div>
                    }
                </>
                : <div className="flex flex-col mt-2 alert alert-danger">
                    Could not connect to JetBrains SpaceCode. {spaceRequestError}
                    <div className="flex flex-row gap-2 mt-2">
                        <button className="jenkins-button" onClick={onRefreshButtonClick}>
                            <img src={`${rootURL}/plugin/jetbrains-space/icons/reload.svg`} alt="" width="16" height="16"/>
                            Retry
                        </button>
                    </div>
                </div>
        );
};

interface SpaceAppState {
    app?: SpaceApp;
    isLoading: boolean;
    spaceRequestError?: string;
    eventSource?: EventSource,
    refresh: () => void;
}

(window as any).spaceAppConnections = (window as any).spaceAppConnections || {};
const spaceAppConnections = (window as any).spaceAppConnections as { [key: string]: UseBoundStore<StoreApi<SpaceAppState>> };

export function resetProjectAppState(id: string) {
    delete spaceAppConnections[id];
}

export function getSpaceAppState(id: string, fetch: () => Promise<SpaceApp | string>) {
    spaceAppConnections[id] = spaceAppConnections[id] ||
        createState<SpaceAppState>((set) => {
            var eventSource: EventSource | null = null;
            const state = {
                isLoading: true,
                refresh: async () => {
                    set((state) => ({ ...state, isLoading: true }));
                    try {
                        const result = await fetch();
                        if (typeof result === "string") {
                            set((state) => ({
                                isLoading: false,
                                spaceRequestError: (result.length < 500) ? result : "",
                                refresh: state.refresh,
                            }));
                            eventSource?.close();
                            eventSource = null;
                        } else {
                            set((state) => ({
                                app: result,
                                isLoading: false,
                                refresh: state.refresh,
                            }));
                            if (result.missingPermissions.length > 0) {
                                eventSource = new EventSource(`${rootURL}/jb-space-projects/waitForPermissionsApprove?appId=${result.appId}`);
                                eventSource.onmessage = () => {
                                    state.refresh();
                                    eventSource?.close();
                                };
                            } else {
                                eventSource?.close();
                                eventSource = null;
                            }
                        }
                    } catch (error) {
                        set((state) => ({
                            isLoading: false,
                            spaceRequestError: error ? error.toString() : "",
                            refresh: state.refresh,
                        }));
                        eventSource?.close();
                        eventSource = null;
                    }
                }
            };
            state.refresh();
            return state;
        });
    return spaceAppConnections[id];
}
