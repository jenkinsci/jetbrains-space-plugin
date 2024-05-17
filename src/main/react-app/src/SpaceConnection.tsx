import React, {FC} from "react";
import {fetchSpaceApp} from "./jenkinsPluginClient";
import {SpaceAppInfoComponent} from "./SpaceAppInfoComponent";

export const SpaceConnection: FC<{ id: string, spaceUrl: string }> = ({ id, spaceUrl }) => {
    return <div className="flex flex-col gap-1">
        <div className="flex flex-row gap-1">
            <span className="jenkins-form-label">Server URL:</span>
            <a href={spaceUrl} rel="noreferrer" target="_blank">{spaceUrl}</a>
        </div>

        <SpaceAppInfoComponent id={id} fetchSpaceApp={() => fetchSpaceApp(id)} showOnlyErrors={false}  />
    </div>;
};
