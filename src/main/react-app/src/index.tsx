import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import {getCurrentItemFullPathFromURL, SpaceProjectConnection, SpaceProjectConnectionProps} from "./SpaceProjectConnection";
import {SpaceConnection} from "./SpaceConnection";
import {SpaceAppInfoComponent} from "./SpaceAppInfoComponent";
import {fetchProjectSpaceApp} from "./jenkinsPluginClient";
import {SpaceRepoConnection, SpaceRepoConnectionProps} from "./SpaceRepoConnection";

export const mountSpaceConnection = (rootElement: HTMLElement, id: string, baseUrl: string) => {
    const root = ReactDOM.createRoot(rootElement);
    root.render(<SpaceConnection id={id} spaceUrl={baseUrl} /> );
    return root;
};

export const mountSpaceProjectConnection = (rootElement: HTMLElement, inputs: SpaceProjectConnectionProps) => {
    const root = ReactDOM.createRoot(rootElement);
    root.render(<SpaceProjectConnection {...inputs} /> );
    return root;
};

export const mountSpaceRepoConnection = (rootElement: HTMLElement, inputs: SpaceRepoConnectionProps) => {
    const root = ReactDOM.createRoot(rootElement);
    root.render(<SpaceRepoConnection {...inputs} /> );
    return root;
};

export const mountSpaceProjectAppErrorsComponent = (rootElement: HTMLElement, onProblemsFixed?: () => void) => {
    const root = ReactDOM.createRoot(rootElement);
    const jenkinsJob = getCurrentItemFullPathFromURL()
    if (jenkinsJob == null) {
        console.error("Project connection component can only be used on the job configuration page");
        return null;
    }

    root.render(<SpaceAppInfoComponent
        id={jenkinsJob}
        fetchSpaceApp={() => fetchProjectSpaceApp(jenkinsJob)}
        showOnlyErrors={true}
        onProblemsFixed={onProblemsFixed}
    />);
    return root;
}
