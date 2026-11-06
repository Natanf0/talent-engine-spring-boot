package br.com.careermatcher.listings;

import java.util.Map;

/** Optional employment labels/properties are parameterized for databases without resume schema tokens yet. */
public final class TalentPoolScope {
    private TalentPoolScope() {}
    public static final Map<String,Object> PARAMETERS = Map.of(
            "talentResumeType","HAS", "talentExperienceType","HasExperience",
            "talentResumeLabel","RESUME", "talentExperienceLabel","EXPERIENCE",
            "talentConfirmedProperty","confirmed", "talentCurrentProperty","currentJob",
            "talentCompanyProperty","confirmedCompanyId");

    /** Aliases and the company parameter are application constants, never client input. */
    public static String notInternal(String candidate, String companyParameter) {
        return "NOT EXISTS { MATCH ("+candidate+")-[resumeLink]->(resume)-[experienceLink]->(employment) "
                + "WHERE type(resumeLink)=$talentResumeType AND type(experienceLink)=$talentExperienceType "
                + "AND $talentResumeLabel IN labels(resume) AND $talentExperienceLabel IN labels(employment) "
                + "AND employment[$talentConfirmedProperty]=true AND employment[$talentCurrentProperty]=true "
                + "AND employment[$talentCompanyProperty]="+companyParameter+" }";
    }
}
