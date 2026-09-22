package com.github.lisama569.biodbaggregatorjava.resolve;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 解析规则的单一配置入口。规则版本变化（顺序/成员变化）后，
 * 旧快照仍绑定其生成时的规则版本，历史结论可复现。
 */
@Configuration
public class ResolutionRulesConfig {

    /**
     * 默认规则 v1：
     * <ul>
     *   <li>命名空间优先级：uniprot &gt; refseq &gt; ensembl &gt; geneId &gt; proteinId；</li>
     *   <li>来源优先级（属性裁决）：ensembl-snapshots &gt; uniprot-snapshots &gt; ncbi-snapshots。</li>
     * </ul>
     */
    @Bean
    public ResolutionRules defaultResolutionRules() {
        return new ResolutionRules(
                "rules-v1",
                List.of("uniprot", "refseq", "ensembl", "geneId", "proteinId"),
                List.of("ensembl-snapshots", "uniprot-snapshots", "ncbi-snapshots")
        );
    }
}
