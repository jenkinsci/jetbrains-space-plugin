package org.jetbrains.space.jenkins.trigger;

import hudson.model.CauseAction;
import jenkins.triggers.SCMTriggerItem;

public class NoIdeaFreeze {
    
    public static void triggerBuild(SCMTriggerItem job, CauseAction action) {
        job.scheduleBuild2(job.getQuietPeriod(), action);
    }
}
