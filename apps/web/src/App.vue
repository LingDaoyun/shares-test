<template>
  <main class="app-shell">
    <header class="hero-bar">
      <div class="hero-copy">
        <p class="eyebrow">{{ currentPageMeta.eyebrow }}</p>
        <h1>{{ currentPageMeta.title }}</h1>
        <p class="hero-description">{{ currentPageMeta.description }}</p>
      </div>

      <div class="hero-side">
        <div class="status-strip">
          <article class="status-chip">
            <span>模型</span>
            <strong>{{ llmConfigLabel }}</strong>
          </article>
          <article class="status-chip">
            <span>观察池</span>
            <strong>{{ watchlist.length }} 个候选</strong>
          </article>
          <article class="status-chip">
            <span>行情样本</span>
            <strong>{{ liveCompanyCount }} 个实时样本</strong>
          </article>
        </div>

        <div class="topbar-actions">
          <el-button plain @click="navigateTo(currentPage === 'settings' ? 'overview' : 'settings')">
            {{ currentPage === 'settings' ? '返回总览' : '系统配置' }}
          </el-button>
          <el-tooltip content="刷新全部数据">
            <el-button :icon="Refresh" circle :loading="loading" @click="loadAll" />
          </el-tooltip>
          <el-button
            v-if="currentPage === 'companies' && activeCompany"
            type="primary"
            :icon="TrendCharts"
            @click="runEvaluation"
          >
            规则试算
          </el-button>
        </div>
      </div>
    </header>

    <nav class="module-menu" aria-label="模块菜单">
      <button
        v-for="item in navigationItems"
        :key="item.key"
        :class="['menu-pill', { active: currentPage === item.key }]"
        type="button"
        @click="navigateTo(item.key)"
      >
        <span>{{ item.label }}</span>
        <small>{{ item.count }}</small>
      </button>
    </nav>

    <section v-if="currentPage === 'overview'" class="page-stack">
      <section class="metric-grid">
        <article class="metric-card">
          <span>政策主题</span>
          <strong>{{ themes.length }}</strong>
          <small>默认按推荐指数排列</small>
        </article>
        <article class="metric-card">
          <span>公司池</span>
          <strong>{{ companies.length }}</strong>
          <small>第 1 页始终展示高推荐指数样本</small>
        </article>
        <article class="metric-card">
          <span>规则版本</span>
          <strong>{{ totalRuleVersions }}</strong>
          <small>规则优先级已独立成页</small>
        </article>
        <article class="metric-card">
          <span>观察池</span>
          <strong>{{ watchlist.length }}</strong>
          <small>按推荐指数降序输出</small>
        </article>
      </section>

      <section class="workspace-grid overview-grid">
        <article class="panel spotlight-panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">TOP POLICY</p>
              <h2>政策热度分布</h2>
            </div>
            <el-tag type="success" effect="plain">推荐指数优先</el-tag>
          </div>
          <div ref="themeChartRef" class="chart"></div>
          <div class="mini-list">
            <article v-for="theme in topThemes" :key="theme.themeCode" class="mini-row">
              <div>
                <strong>{{ theme.name }}</strong>
                <span>{{ theme.policyLevel }} / {{ theme.timeHorizon }}</span>
              </div>
              <b>{{ formatScore(policyRecommendation(theme)) }}</b>
            </article>
          </div>
        </article>

        <article class="panel spotlight-panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">TOP COMPANY</p>
              <h2>公司池焦点样本</h2>
            </div>
            <el-button text @click="navigateTo('companies')">进入公司池</el-button>
          </div>
          <template v-if="topCompany">
            <div class="feature-score">
              <span>推荐指数</span>
              <strong>{{ formatScore(companyRecommendation(topCompany)) }}</strong>
            </div>
            <div class="feature-copy">
              <h3>{{ topCompany.name }} <small>{{ topCompany.symbol }}</small></h3>
              <p>{{ topCompany.industry }} / {{ topCompany.dataSource }}</p>
            </div>
            <div class="tag-line">
              <el-tag v-for="asset in topCompany.coreAssets.slice(0, 3)" :key="asset" effect="plain">{{ asset }}</el-tag>
            </div>
            <div class="detail-grid">
              <article>
                <span>主题相关度</span>
                <strong>{{ formatScore(Number(topCompany.themeRelevance)) }}</strong>
              </article>
              <article>
                <span>PE(TTM)</span>
                <strong>{{ formatNumber(topCompany.peTtm) }}</strong>
              </article>
              <article>
                <span>PB</span>
                <strong>{{ formatNumber(topCompany.pbRatio) }}</strong>
              </article>
            </div>
          </template>
          <el-empty v-else description="等待公司池数据" />
        </article>

        <article class="panel spotlight-panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">TOP WATCHLIST</p>
              <h2>观察池优先级</h2>
            </div>
            <el-button text @click="navigateTo('watchlist')">查看全部</el-button>
          </div>
          <div class="mini-list">
            <article v-for="entry in topWatchlist" :key="entry.symbol" class="mini-row watch-row-compact">
              <div>
                <strong>{{ entry.companyName }}</strong>
                <span>{{ entry.symbol }} / {{ entry.decision }}</span>
              </div>
              <b>{{ formatScore(watchlistRecommendation(entry)) }}</b>
            </article>
          </div>
        </article>
      </section>
    </section>

    <section v-else-if="currentPage === 'ai'" class="page-stack">
      <section class="workspace-grid ai-grid">
        <div class="panel ai-input-panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">AI TREND ANALYSIS</p>
              <h2>政策隐含趋势</h2>
            </div>
            <div class="ai-config-tags">
              <el-tag :type="llmProviderTagType" effect="plain">{{ llmConfigLabel }}</el-tag>
              <el-tag :type="llmConfig?.apiKeyConfigured ? 'success' : 'danger'" effect="plain">
                {{ llmConfig?.apiKeyConfigured ? 'Key 已配置' : 'Key 缺失' }}
              </el-tag>
            </div>
          </div>

          <el-alert
            v-if="llmConfig?.provider === 'kimi-code'"
            class="inline-alert"
            type="warning"
            :closable="false"
            title="Kimi Code 当前限制为编码 Agent 场景，投研后端建议切换 Moonshot 开放平台。"
          />

          <el-form label-position="top" class="trend-form">
            <div class="form-grid">
              <el-form-item label="文档标题">
                <el-input v-model="trendForm.documentTitle" maxlength="120" />
              </el-form-item>
              <el-form-item label="发布机构">
                <el-input v-model="trendForm.sourceOrganization" maxlength="60" />
              </el-form-item>
              <el-form-item label="发布时间">
                <el-input v-model="trendForm.publishedAt" maxlength="20" />
              </el-form-item>
              <el-form-item label="来源链接">
                <el-input v-model="trendForm.sourceUrl" maxlength="240" />
              </el-form-item>
            </div>

            <el-form-item label="正文节选">
              <el-input
                v-model="trendForm.contentExcerpt"
                type="textarea"
                :rows="5"
                maxlength="12000"
                show-word-limit
              />
            </el-form-item>

            <div class="form-grid">
              <el-form-item label="关注主题">
                <el-select
                  v-model="trendForm.focusThemes"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  class="full-width"
                >
                  <el-option v-for="theme in themeOptions" :key="theme" :label="theme" :value="theme" />
                </el-select>
              </el-form-item>
              <el-form-item label="已知公司池">
                <el-select
                  v-model="trendForm.knownCompanies"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  class="full-width"
                >
                  <el-option v-for="company in companyOptions" :key="company" :label="company" :value="company" />
                </el-select>
              </el-form-item>
            </div>

            <div class="ai-actions">
              <el-button :icon="DocumentChecked" :loading="previewLoading" @click="previewTrend">提示词预览</el-button>
              <el-button type="primary" :icon="MagicStick" :loading="aiLoading" @click="runTrendAnalysis">
                运行 AI 分析
              </el-button>
              <el-button :icon="Cpu" :loading="configLoading" @click="refreshLlmConfig">刷新模型</el-button>
            </div>
          </el-form>
        </div>

        <div class="panel ai-result-panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">AI OUTPUT</p>
              <h2>结构化结论</h2>
            </div>
            <div v-if="trendAnalysis" class="ai-config-tags">
              <el-tag type="info" effect="plain">{{ trendAnalysis.promptVersion }}</el-tag>
              <el-tag :type="trendAnalysis.cached ? 'success' : 'warning'" effect="plain">{{ trendAnalysisStatus }}</el-tag>
            </div>
          </div>

          <el-alert v-if="aiError" class="inline-alert" type="error" :closable="false" :title="aiError" />

          <template v-if="trendAnalysis">
            <article class="assessment-card">
              <span>总体判断</span>
              <strong>{{ overallSummary }}</strong>
              <small>{{ overallNextAction }} / 置信度 {{ overallConfidence }} / {{ trendAnalysisStatus }}</small>
            </article>

            <div class="ai-result-list">
              <article v-for="trend in hiddenTrendCards" :key="trend.name" class="trend-card">
                <div class="trend-card-head">
                  <div>
                    <strong>{{ trend.name }}</strong>
                    <span>{{ trend.type }} / 证据 {{ trend.evidenceStrength }}</span>
                  </div>
                  <strong class="score">{{ trend.strength }}</strong>
                </div>
                <ol>
                  <li v-for="item in trend.logicChain" :key="item">{{ item }}</li>
                </ol>
                <div class="tag-line">
                  <el-tag v-for="profile in trend.profiles" :key="profile" effect="plain">{{ profile }}</el-tag>
                </div>
              </article>
            </div>

            <div class="ai-split">
              <div>
                <h3>监控指标</h3>
                <article v-for="item in monitoringCards" :key="item.indicator" class="compact-row">
                  <strong>{{ item.indicator }}</strong>
                  <span>{{ item.direction }} / {{ item.source }}</span>
                </article>
              </div>
              <div>
                <h3>反证条件</h3>
                <article v-for="item in counterEvidenceCards" :key="item.condition" class="compact-row">
                  <strong>{{ item.condition }}</strong>
                  <span>{{ item.severity }} / {{ item.impact }}</span>
                </article>
              </div>
            </div>
          </template>

          <template v-else-if="trendPromptPreview">
            <div class="prompt-preview">
              <strong>{{ trendPromptPreview.name }}@{{ trendPromptPreview.version }}</strong>
              <p>{{ promptPreviewExcerpt }}</p>
            </div>
          </template>

          <el-empty v-else description="等待分析结果" />

          <div v-if="trendHistory.length > 0" class="ai-split">
            <div>
              <h3>最近归档</h3>
              <article v-for="item in trendHistory" :key="item.recordId" class="compact-row">
                <strong>{{ item.documentTitle }}</strong>
                <span>{{ item.analysisDate }} / {{ item.provider }} / 置信度 {{ item.overallConfidence ?? '待补充' }}</span>
              </article>
            </div>
            <div>
              <h3>后续动作</h3>
              <article v-for="item in trendHistory" :key="`${item.recordId}-action`" class="compact-row">
                <strong>{{ item.nextAction ?? '待补充' }}</strong>
                <span>{{ item.overallSummary ?? '等待摘要' }}</span>
              </article>
            </div>
          </div>
        </div>
      </section>
    </section>

    <section v-else-if="currentPage === 'policy'" class="page-stack">
      <article class="panel section-banner">
        <div>
          <p class="eyebrow">POLICY LIST</p>
          <h2>政策主题列表</h2>
        </div>
        <p>按推荐指数从高到低排序，分页后的第一页永远是当前最强主题。</p>
      </article>

      <article class="panel list-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">RANKED THEMES</p>
            <h2>推荐指数排序</h2>
          </div>
          <el-tag type="success" effect="plain">按推荐指数降序</el-tag>
        </div>

        <el-table :data="pagedThemes" class="data-table" row-key="themeCode">
          <el-table-column label="排名" width="84">
            <template #default="{ $index }">{{ rankNumber(policyPager.page, policyPager.pageSize, $index) }}</template>
          </el-table-column>
          <el-table-column prop="name" label="主题" min-width="220" />
          <el-table-column label="推荐指数" width="120" align="right">
            <template #default="{ row }">
              <span class="score-badge">{{ formatScore(policyRecommendation(row)) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="policyLevel" label="政策级别" width="140" />
          <el-table-column prop="timeHorizon" label="时间周期" width="140" />
          <el-table-column label="信号数" width="100" align="right">
            <template #default="{ row }">{{ row.signals.length }}</template>
          </el-table-column>
          <el-table-column label="产业链环节" min-width="240">
            <template #default="{ row }">
              <div class="tag-line">
                <el-tag v-for="segment in row.chainSegments.slice(0, 3)" :key="segment" effect="plain">{{ segment }}</el-tag>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="policyPager.page"
            v-model:page-size="policyPager.pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :page-sizes="[6, 10, 20]"
            :total="sortedThemes.length"
            @size-change="policyPager.page = 1"
          />
        </div>
      </article>
    </section>

    <section v-else-if="currentPage === 'companies'" class="page-stack">
      <article class="panel section-banner">
        <div>
          <p class="eyebrow">COMPANY LIST</p>
          <h2>主题公司池</h2>
        </div>
        <p>推荐指数综合了观察池评分、主题相关度、估值和年报匹配情况，默认按高到低分页展示。</p>
      </article>

      <section class="workspace-grid wide-left">
        <div class="panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">RANKED COMPANIES</p>
              <h2>公司列表</h2>
            </div>
            <el-select v-model="selectedSymbol" class="company-select" @change="focusCompany">
              <el-option
                v-for="company in sortedCompanies"
                :key="company.symbol"
                :label="`${company.name} / ${company.symbol}`"
                :value="company.symbol"
              />
            </el-select>
          </div>

          <el-table
            :data="pagedCompanies"
            row-key="symbol"
            class="data-table company-table"
            highlight-current-row
            :current-row-key="selectedSymbol"
            :row-class-name="companyRowClassName"
            @row-click="selectCompany"
          >
            <el-table-column label="排名" width="84">
              <template #default="{ $index }">{{ rankNumber(companyPager.page, companyPager.pageSize, $index) }}</template>
            </el-table-column>
            <el-table-column prop="symbol" label="代码" width="100" />
            <el-table-column prop="name" label="公司" min-width="160" />
            <el-table-column prop="industry" label="行业" min-width="140" />
            <el-table-column label="推荐指数" width="120" align="right">
              <template #default="{ row }">
                <span class="score-badge">{{ formatScore(companyRecommendation(row)) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最新价" width="100" align="right">
              <template #default="{ row }">{{ formatNumber(row.latestPrice) }}</template>
            </el-table-column>
            <el-table-column label="涨跌幅" width="100" align="right">
              <template #default="{ row }">
                <span :class="changeClass(row.changePercent)">{{ formatPercent(row.changePercent) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="PE/PB" width="140" align="right">
              <template #default="{ row }">{{ formatNumber(row.peTtm) }} / {{ formatNumber(row.pbRatio) }}</template>
            </el-table-column>
            <el-table-column label="主题相关度" width="130">
              <template #default="{ row }">
                <el-progress :percentage="Number(row.themeRelevance)" :stroke-width="7" :show-text="false" />
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-bar">
            <el-pagination
              v-model:current-page="companyPager.page"
              v-model:page-size="companyPager.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 30]"
              :total="sortedCompanies.length"
              @size-change="handleCompanyPageSizeChange"
            />
          </div>
        </div>

        <aside class="panel detail-panel">
          <div class="panel-head compact">
            <div>
              <p class="eyebrow">EVIDENCE</p>
              <h2>{{ activeCompany?.name ?? '选择公司' }}</h2>
            </div>
            <div class="ai-config-tags">
              <el-tag v-if="activeResearch" type="success" effect="plain">{{ activeResearch.stageLabel }}</el-tag>
              <el-tag v-else type="info" effect="plain">{{ activeCompany?.symbol }}</el-tag>
            </div>
          </div>
          <template v-if="activeCompany">
            <div class="quote-strip">
              <div>
                <span>{{ activeResearch ? '研究总分' : '推荐指数' }}</span>
                <strong>{{ formatScore(activeResearch?.overallScore ?? companyRecommendation(activeCompany)) }}</strong>
              </div>
              <div>
                <span>PE(TTM)</span>
                <strong>{{ formatNumber(activeCompany.peTtm) }}</strong>
              </div>
              <div>
                <span>PB</span>
                <strong>{{ formatNumber(activeCompany.pbRatio) }}</strong>
              </div>
            </div>
            <el-alert
              v-if="researchLoading"
              class="inline-alert"
              type="info"
              :closable="false"
              title="正在读取公司研究视图"
            />
            <div class="analysis-card">
              <span>分析摘要</span>
              <strong>{{ activeCompanyAnalysis.verdict }}</strong>
              <p>{{ activeCompanyAnalysis.valuation }}</p>
              <p>{{ activeCompanyAnalysis.quality }}</p>
            </div>
            <el-alert
              v-if="consensusLoading"
              class="inline-alert"
              type="info"
              :closable="false"
              title="正在组织多 Agent 共识讨论"
            />
            <div v-if="activeConsensus" class="evidence-block committee-block">
              <div class="committee-head">
                <div>
                  <h3>Agent 共识</h3>
                  <p>{{ activeConsensus.consensusReason }}</p>
                </div>
                <div class="committee-actions">
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    :icon="MagicStick"
                    :loading="aiConsensusLoading"
                    @click="runAiConsensus()"
                  >
                    AI 辩论增强
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :icon="DocumentChecked"
                    :loading="isEvidenceReviewLoading(activeConsensus.symbol)"
                    @click="runEvidenceReview(activeConsensus.symbol)"
                  >
                    运行证据复核
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :icon="Check"
                    :loading="isInvestmentDecisionLoading(activeConsensus.symbol)"
                    @click="runInvestmentDecision(activeConsensus.symbol)"
                  >
                    投资门禁
                  </el-button>
                  <div class="feature-score compact">
                    <span>{{ activeConsensus.consensusLabel }}</span>
                    <strong>{{ formatScore(activeConsensus.consensusScore) }}</strong>
                  </div>
                </div>
              </div>
              <div v-if="activeConsensus.aiSummary || activeConsensus.aiWarnings.length > 0" class="ai-consensus-note">
                <div v-if="activeConsensus.aiSummary">
                  <strong>AI 委员会总结</strong>
                  <span>{{ activeConsensus.aiSummary }}</span>
                </div>
                <p v-if="activeConsensus.aiSuggestedStage">
                  建议阶段：{{ activeConsensus.aiSuggestedStage }}，确定性分数仍以规则共识为准。
                </p>
                <p v-for="warning in activeConsensus.aiWarnings" :key="warning" class="danger-text">{{ warning }}</p>
                <small v-if="activeConsensus.aiProvider && activeConsensus.aiModel">
                  {{ activeConsensus.aiProvider }} / {{ activeConsensus.aiModel }}
                </small>
              </div>
              <div class="vote-strip">
                <article>
                  <span>支持</span>
                  <strong>{{ activeConsensus.supportCount }}</strong>
                </article>
                <article>
                  <span>观察</span>
                  <strong>{{ activeConsensus.watchCount }}</strong>
                </article>
                <article>
                  <span>复核</span>
                  <strong>{{ activeConsensus.reviewCount }}</strong>
                </article>
                <article class="danger">
                  <span>否决</span>
                  <strong>{{ activeConsensus.vetoCount }}</strong>
                </article>
              </div>
              <div class="agent-opinion-list">
                <article
                  v-for="opinion in activeConsensus.opinions"
                  :key="opinion.agentCode"
                  :class="['agent-opinion-row', opinion.vote.toLowerCase()]"
                >
                  <div class="agent-opinion-head">
                    <div>
                      <strong>{{ opinion.agentName }}</strong>
                      <span>{{ opinion.perspective }}</span>
                    </div>
                    <b>{{ opinion.voteLabel }} / {{ formatScore(opinion.score) }}</b>
                  </div>
                  <p v-if="opinion.supports.length > 0">支持：{{ opinion.supports.slice(0, 2).join(' / ') }}</p>
                  <p v-if="opinion.objections.length > 0">反对：{{ opinion.objections.slice(0, 2).join(' / ') }}</p>
                  <div v-if="opinion.evidenceChecks.length > 0" class="evidence-check-list">
                    <article
                      v-for="check in opinion.evidenceChecks"
                      :key="`${opinion.agentCode}-${check.requirement}`"
                      :class="['evidence-check-row', check.status.toLowerCase()]"
                    >
                      <div>
                        <strong>{{ check.requirement }}</strong>
                        <span>{{ check.source }} / {{ check.evidenceText }}</span>
                      </div>
                      <el-tag size="small" :type="evidenceStatusType(check.status)" effect="plain">
                        {{ check.statusLabel }}
                      </el-tag>
                    </article>
                  </div>
                  <div v-if="opinion.aiArgument || opinion.aiCounterEvidence || opinion.aiConfidenceNote" class="agent-ai-note">
                    <p v-if="opinion.aiArgument">AI 论证：{{ opinion.aiArgument }}</p>
                    <p v-if="opinion.aiCounterEvidence">AI 反证：{{ opinion.aiCounterEvidence }}</p>
                    <p v-if="opinion.aiConfidenceNote">信心说明：{{ opinion.aiConfidenceNote }}</p>
                  </div>
                </article>
              </div>
              <div v-if="activeConsensus.disagreements.length > 0" class="signal-list danger">
                <strong>主要分歧</strong>
                <span>{{ activeConsensus.disagreements.slice(0, 4).join(' / ') }}</span>
              </div>
              <div v-if="activeConsensus.requiredEvidence.length > 0" class="signal-list">
                <strong>补证清单</strong>
                <span>{{ activeConsensus.requiredEvidence.slice(0, 5).join(' / ') }}</span>
              </div>
              <div v-if="activeCompanyEvidenceReview" class="evidence-review-block">
                <div class="evidence-review-head">
                  <div>
                    <strong>{{ activeCompanyEvidenceReview.reviewLabel }}</strong>
                    <span>{{ activeCompanyEvidenceReview.conclusions.join(' / ') }}</span>
                  </div>
                  <el-tag :type="reviewStatusType(activeCompanyEvidenceReview.reviewStage)" effect="plain">
                    {{ activeCompanyEvidenceReview.totalItems }} 项
                  </el-tag>
                </div>
                <div class="review-metric-strip">
                  <article>
                    <span>已核实</span>
                    <strong>{{ activeCompanyEvidenceReview.verifiedCount }}</strong>
                  </article>
                  <article>
                    <span>部分补到</span>
                    <strong>{{ activeCompanyEvidenceReview.partialCount }}</strong>
                  </article>
                  <article class="danger">
                    <span>未命中</span>
                    <strong>{{ activeCompanyEvidenceReview.notFoundCount }}</strong>
                  </article>
                  <article>
                    <span>源阻塞</span>
                    <strong>{{ activeCompanyEvidenceReview.blockedCount }}</strong>
                  </article>
                </div>
                <div class="review-step-list">
                  <article v-for="step in activeCompanyEvidenceReview.steps" :key="step.stepCode" class="review-step-row">
                    <strong>{{ step.actor }}</strong>
                    <span>{{ step.conclusion }}</span>
                    <p v-if="step.evidenceRefs.length > 0">{{ step.evidenceRefs.join(' / ') }}</p>
                  </article>
                </div>
                <div class="review-item-list">
                  <article
                    v-for="item in activeCompanyEvidenceReview.items"
                    :key="`${item.agentCode}-${item.requirement}`"
                    :class="['review-item-row', item.reviewStatus.toLowerCase()]"
                  >
                    <div>
                      <strong>{{ item.agentName }} / {{ item.requirement }}</strong>
                      <span>{{ item.searchScope }} / {{ item.source }} / {{ item.evidenceText }}</span>
                      <p>{{ item.verdict }}；下一步：{{ item.nextAction }}</p>
                    </div>
                    <el-tag size="small" :type="reviewStatusType(item.reviewStatus)" effect="plain">
                      {{ item.reviewStatusLabel }}
                    </el-tag>
                  </article>
                </div>
              </div>
              <div v-if="activeCompanyInvestmentDecision" class="decision-block">
                <div class="decision-head">
                  <div>
                    <strong>{{ activeCompanyInvestmentDecision.actionLabel }}</strong>
                    <span>{{ activeCompanyInvestmentDecision.actionReason }}</span>
                  </div>
                  <el-tag :type="decisionStatusType(activeCompanyInvestmentDecision.actionStage)" effect="plain">
                    {{ formatScore(activeCompanyInvestmentDecision.decisionScore) }}
                  </el-tag>
                </div>
                <p class="decision-note">{{ activeCompanyInvestmentDecision.complianceNote }}</p>
                <div class="decision-metric-strip">
                  <article>
                    <span>通过</span>
                    <strong>{{ activeCompanyInvestmentDecision.passCount }}</strong>
                  </article>
                  <article>
                    <span>观察</span>
                    <strong>{{ activeCompanyInvestmentDecision.watchCount }}</strong>
                  </article>
                  <article class="danger">
                    <span>阻断</span>
                    <strong>{{ activeCompanyInvestmentDecision.blockCount }}</strong>
                  </article>
                  <article class="danger">
                    <span>失败</span>
                    <strong>{{ activeCompanyInvestmentDecision.failCount }}</strong>
                  </article>
                </div>
                <div class="financial-history-strip">
                  <article>
                    <span>财务序列</span>
                    <strong>{{ activeCompanyInvestmentDecision.financialHistory.statusLabel }}</strong>
                    <p>{{ activeCompanyInvestmentDecision.financialHistory.annualPointCount }} 个年度样本 / 质量分 {{ formatScore(activeCompanyInvestmentDecision.financialHistory.qualityScore) }}</p>
                  </article>
                  <article>
                    <span>平均 ROE</span>
                    <strong>{{ formatRatioPercent(activeCompanyInvestmentDecision.financialHistory.averageRoe) }}</strong>
                    <p>平均毛利率 {{ formatRatioPercent(activeCompanyInvestmentDecision.financialHistory.averageGrossMargin) }}</p>
                  </article>
                  <article>
                    <span>现金流</span>
                    <strong>{{ activeCompanyInvestmentDecision.financialHistory.positiveCashFlowYears }} 年为正</strong>
                    <p>营收同比为负 {{ activeCompanyInvestmentDecision.financialHistory.negativeRevenueGrowthYears }} 年</p>
                  </article>
                  <article>
                    <span>数据缺口</span>
                    <strong>{{ activeCompanyInvestmentDecision.financialHistory.dataGaps.length }}</strong>
                    <p>{{ activeCompanyInvestmentDecision.financialHistory.dataGaps.slice(0, 2).join(' / ') }}</p>
                  </article>
                </div>
                <div class="valuation-history-strip">
                  <article>
                    <span>估值序列</span>
                    <strong>{{ activeCompanyInvestmentDecision.valuationHistory.statusLabel }}</strong>
                    <p>{{ activeCompanyInvestmentDecision.valuationHistory.sampleCount }} 个年度样本</p>
                  </article>
                  <article>
                    <span>当前估值</span>
                    <strong>PE {{ formatNumber(activeCompanyInvestmentDecision.valuationHistory.currentPe) }}</strong>
                    <p>PB {{ formatNumber(activeCompanyInvestmentDecision.valuationHistory.currentPb) }}</p>
                  </article>
                  <article>
                    <span>历史分位</span>
                    <strong>PE {{ formatRatioPercent(activeCompanyInvestmentDecision.valuationHistory.pePercentile) }}</strong>
                    <p>PB {{ formatRatioPercent(activeCompanyInvestmentDecision.valuationHistory.pbPercentile) }}</p>
                  </article>
                  <article>
                    <span>样本口径</span>
                    <strong>{{ activeCompanyInvestmentDecision.valuationHistory.dataGaps.length }}</strong>
                    <p>{{ activeCompanyInvestmentDecision.valuationHistory.dataGaps.slice(0, 2).join(' / ') || '暂无缺口' }}</p>
                  </article>
                </div>
                <div class="peer-valuation-strip">
                  <article>
                    <span>同业估值</span>
                    <strong>{{ activeCompanyInvestmentDecision.valuationHistory.peerValuation.scopeLabel }}</strong>
                    <p>{{ activeCompanyInvestmentDecision.valuationHistory.peerValuation.peerCount }} 个可比样本</p>
                  </article>
                  <article>
                    <span>行业中位数</span>
                    <strong>PE {{ formatNumber(activeCompanyInvestmentDecision.valuationHistory.peerValuation.medianPe) }}</strong>
                    <p>PB {{ formatNumber(activeCompanyInvestmentDecision.valuationHistory.peerValuation.medianPb) }}</p>
                  </article>
                  <article>
                    <span>同业分位</span>
                    <strong>PE {{ formatRatioPercent(activeCompanyInvestmentDecision.valuationHistory.peerValuation.pePeerPercentile) }}</strong>
                    <p>PB {{ formatRatioPercent(activeCompanyInvestmentDecision.valuationHistory.peerValuation.pbPeerPercentile) }}</p>
                  </article>
                  <article>
                    <span>更便宜样本</span>
                    <strong>{{ activeCompanyInvestmentDecision.valuationHistory.peerValuation.cheaperPeCount }} / {{ activeCompanyInvestmentDecision.valuationHistory.peerValuation.cheaperPbCount }}</strong>
                    <p>{{ activeCompanyInvestmentDecision.valuationHistory.peerValuation.dataGaps.slice(0, 1).join(' / ') || '横向样本可用' }}</p>
                  </article>
                </div>
                <div class="decision-gate-list">
                  <article
                    v-for="gate in activeCompanyInvestmentDecision.gates"
                    :key="gate.gateCode"
                    :class="['decision-gate-row', gate.status.toLowerCase()]"
                  >
                    <div>
                      <strong>{{ gate.gateName }}</strong>
                      <span>{{ gate.conclusion }}</span>
                      <p v-if="gate.evidenceRefs.length > 0">{{ gate.evidenceRefs.join(' / ') }}</p>
                    </div>
                    <el-tag size="small" :type="decisionStatusType(gate.status)" effect="plain">
                      {{ gate.statusLabel }}
                    </el-tag>
                  </article>
                </div>
                <div class="decision-section-grid">
                  <article>
                    <strong>长期假设</strong>
                    <span>{{ activeCompanyInvestmentDecision.thesis.join(' / ') }}</span>
                  </article>
                  <article>
                    <strong>买入前条件</strong>
                    <span>{{ activeCompanyInvestmentDecision.buyPreconditions.join(' / ') }}</span>
                  </article>
                  <article>
                    <strong>持有纪律</strong>
                    <span>{{ activeCompanyInvestmentDecision.holdDisciplines.join(' / ') }}</span>
                  </article>
                  <article>
                    <strong>下一步动作</strong>
                    <span>{{ activeCompanyInvestmentDecision.requiredActions.join(' / ') }}</span>
                  </article>
                </div>
                <div class="exit-trigger-list">
                  <article v-for="trigger in activeCompanyInvestmentDecision.exitTriggers" :key="trigger.triggerCode">
                    <div>
                      <strong>{{ trigger.triggerName }}</strong>
                      <span>{{ trigger.condition }}</span>
                      <p>{{ trigger.action }}</p>
                    </div>
                    <el-tag size="small" :type="triggerSeverityType(trigger.severity)" effect="plain">
                      {{ trigger.severity }}
                    </el-tag>
                  </article>
                </div>
              </div>
            </div>
            <div v-if="activeResearch" class="research-score-grid">
              <article v-for="dimension in activeResearch.dimensions" :key="dimension.code" class="research-score-card">
                <div>
                  <span>{{ dimension.name }}</span>
                  <strong>{{ formatScore(dimension.score) }}</strong>
                </div>
                <p>{{ dimension.verdict }}</p>
              </article>
            </div>
            <div v-if="activeResearch" class="evidence-block">
              <h3>证据强度</h3>
              <article v-for="tier in activeResearch.evidenceTiers" :key="tier.code" class="evidence-tier-row">
                <div>
                  <strong>{{ tier.label }}</strong>
                  <span>强度 {{ tier.strength }}</span>
                </div>
                <p>{{ tier.evidenceRefs.length > 0 ? tier.evidenceRefs.join(' / ') : '等待接入更多结构化证据' }}</p>
              </article>
            </div>
            <div v-if="activeResearch?.filingEvidence" class="evidence-block filing-block">
              <div class="filing-block-head">
                <h3>公告证据</h3>
                <el-tag :type="activeResearch.filingEvidence.status === 'LIVE' ? 'success' : 'warning'" effect="plain">
                  {{ activeResearch.filingEvidence.statusLabel }}
                </el-tag>
              </div>
              <div class="filing-signal-grid">
                <article>
                  <span>公告样本</span>
                  <strong>{{ activeResearch.filingEvidence.totalDocuments }}</strong>
                </article>
                <article>
                  <span>正文解析</span>
                  <strong>{{ activeResearch.filingEvidence.parsedDocuments }}</strong>
                </article>
                <article>
                  <span>壁垒线索</span>
                  <strong>{{ activeResearch.filingEvidence.moatSignals.length }}</strong>
                </article>
                <article>
                  <span>风险线索</span>
                  <strong>{{ activeResearch.filingEvidence.riskSignals.length }}</strong>
                </article>
                <article>
                  <span>兑现线索</span>
                  <strong>{{ activeResearch.filingEvidence.validationSignals.length }}</strong>
                </article>
              </div>
              <div v-if="activeResearch.filingEvidence.extractedEvents.length > 0" class="filing-event-list">
                <article
                  v-for="event in activeResearch.filingEvidence.extractedEvents.slice(0, 5)"
                  :key="`${event.documentId}-${event.eventType}-${event.evidenceText}`"
                  :class="['filing-event-row', event.eventType.toLowerCase()]"
                >
                  <div>
                    <strong>{{ event.eventLabel }} / {{ event.severity }} / 置信度 {{ event.confidence }}</strong>
                    <span>{{ event.documentTitle }}</span>
                    <p>{{ event.evidenceText }}</p>
                  </div>
                </article>
              </div>
              <div class="filing-list">
                <article
                  v-for="document in activeResearch.filingEvidence.documents.slice(0, 5)"
                  :key="document.documentId"
                  class="filing-row"
                >
                  <div>
                    <strong>{{ document.title }}</strong>
                    <span>{{ document.source }} / {{ document.category }} / 置信度 {{ document.confidence }}</span>
                  </div>
                  <a
                    v-if="document.downloadUrl || document.sourceUrl"
                    :href="document.downloadUrl ?? document.sourceUrl ?? undefined"
                    target="_blank"
                    rel="noreferrer"
                  >
                    查看
                  </a>
                </article>
              </div>
              <div v-if="activeResearch.filingEvidence.riskSignals.length > 0" class="signal-list danger">
                <strong>风险公告</strong>
                <span>{{ activeResearch.filingEvidence.riskSignals.join(' / ') }}</span>
              </div>
              <div v-if="activeResearch.filingEvidence.validationSignals.length > 0" class="signal-list">
                <strong>兑现公告</strong>
                <span>{{ activeResearch.filingEvidence.validationSignals.join(' / ') }}</span>
              </div>
            </div>
            <div class="evidence-block">
              <h3>后续核查</h3>
              <ul>
                <li v-for="item in activeCompanyAnalysis.nextChecks" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="activeResearch" class="evidence-block">
              <h3>数据缺口</h3>
              <ul>
                <li v-for="gap in activeResearch.dataGaps" :key="gap">{{ gap }}</li>
              </ul>
            </div>
            <div class="evidence-block">
              <h3>核心资产</h3>
              <ul>
                <li v-for="asset in activeCompany.coreAssets" :key="asset">{{ asset }}</li>
              </ul>
            </div>
            <div class="evidence-block">
              <h3>风险点</h3>
              <ul>
                <li v-for="risk in activeCompany.risks" :key="risk">{{ risk }}</li>
              </ul>
            </div>
            <div class="evidence-block">
              <h3>证据链</h3>
              <article v-for="item in activeCompany.evidence" :key="item.sourceTitle" class="evidence-item">
                <span>{{ item.sourceType }} / 置信度 {{ item.confidence }}</span>
                <a v-if="item.url" :href="item.url" target="_blank" rel="noreferrer">{{ item.sourceTitle }}</a>
                <strong v-else>{{ item.sourceTitle }}</strong>
                <p>{{ item.excerpt }}</p>
              </article>
            </div>
          </template>
        </aside>
      </section>

      <section class="workspace-grid">
        <div class="panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">EVALUATION</p>
              <h2>规则试算结果</h2>
            </div>
            <el-button :icon="Search" type="primary" plain @click="runEvaluation">重新试算</el-button>
          </div>
          <div class="evaluation-list">
            <article v-for="result in evaluations" :key="result.ruleCode" class="evaluation-row">
              <div class="evaluation-main">
                <span :class="['status-dot', result.passed ? 'pass' : 'fail']"></span>
                <div>
                  <strong>{{ result.name }}</strong>
                  <small>{{ result.ruleCode }}@{{ result.ruleVersion }} / {{ actionLabel(result.action) }}</small>
                </div>
              </div>
              <strong class="score">{{ Number(result.score).toFixed(2) }}</strong>
            </article>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">WATCHLIST SNAPSHOT</p>
              <h2>同名候选观察</h2>
            </div>
            <el-button text @click="navigateTo('watchlist')">进入观察池</el-button>
          </div>
          <div class="watchlist">
            <article v-for="entry in relatedWatchlist" :key="entry.symbol" class="watch-row">
              <div>
                <strong>{{ entry.companyName }}</strong>
                <span>{{ entry.symbol }} / {{ entry.decision }}</span>
                <p>{{ entry.thesis }}</p>
              </div>
              <strong>{{ formatScore(watchlistRecommendation(entry)) }}</strong>
            </article>
          </div>
        </div>
      </section>
    </section>

    <section v-else-if="currentPage === 'rules'" class="page-stack">
      <article class="panel section-banner">
        <div>
          <p class="eyebrow">RULE CATALOG</p>
          <h2>规则配置列表</h2>
        </div>
        <p>规则页按优先级排序，先看高版本、高权重和拦截性更强的规则。</p>
      </article>

      <article class="panel list-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">RULE ENGINE</p>
            <h2>规则目录</h2>
          </div>
          <el-tag type="warning" effect="plain">按优先级降序</el-tag>
        </div>

        <el-table :data="pagedRules" size="small" class="data-table">
          <el-table-column label="排名" width="84">
            <template #default="{ $index }">{{ rankNumber(rulePager.page, rulePager.pageSize, $index) }}</template>
          </el-table-column>
          <el-table-column prop="ruleCode" label="规则" min-width="170" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column label="优先级" width="100" align="right">
            <template #default="{ row }">
              <span class="score-badge">{{ formatScore(rulePriority(row)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="动作" width="110">
            <template #default="{ row }">
              <el-tag :type="actionType(row.action)" effect="plain">{{ actionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="条件数" width="90" align="right">
            <template #default="{ row }">{{ row.conditions.length }}</template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="280" />
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="rulePager.page"
            v-model:page-size="rulePager.pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :page-sizes="[8, 12, 20]"
            :total="sortedRules.length"
            @size-change="rulePager.page = 1"
          />
        </div>
      </article>
    </section>

    <section v-else-if="currentPage === 'selection'" class="page-stack">
      <article class="panel section-banner">
        <div>
          <p class="eyebrow">AGENT STOCK MEETING</p>
          <h2>Agent 选股会</h2>
        </div>
        <p>全市场公司池不按代码前缀过滤，由五个 Agent 逐只讨论后生成少量候选，所有投票、反证和补证要求都可追溯。</p>
        <el-button type="primary" :icon="DocumentChecked" :loading="selectionLoading" @click="loadAgentSelection">
          生成候选
        </el-button>
      </article>

      <section v-if="selectionReport" class="selection-layout">
        <div class="panel selection-list-panel">
          <div class="panel-head compact">
            <div>
              <p class="eyebrow">SHORTLIST</p>
              <h2>入选候选</h2>
            </div>
            <el-tag effect="plain">复核 {{ selectionReport.reviewedCount }} / 全市场 {{ selectionReport.universeCount }}</el-tag>
          </div>

          <article
            v-for="candidate in selectionReport.candidates"
            :key="candidate.symbol"
            :class="['selection-row', { active: activeSelectionCandidate?.symbol === candidate.symbol }]"
            @click="selectSelectionCandidate(candidate.symbol)"
          >
            <div>
              <p class="eyebrow">RANK {{ candidate.rank }}</p>
              <strong>{{ candidate.companyName }}</strong>
              <span>{{ candidate.symbol }} / {{ candidate.market }} / {{ candidate.industry }}</span>
            </div>
            <div class="feature-score compact">
              <span>{{ candidate.selectionLabel }}</span>
              <strong>{{ formatScore(candidate.finalScore) }}</strong>
            </div>
          </article>
        </div>

        <article v-if="activeSelectionCandidate" class="panel selection-detail-panel">
          <div class="panel-head">
            <div>
              <p class="eyebrow">TRACEABLE DISCUSSION</p>
              <h2>{{ activeSelectionCandidate.companyName }}</h2>
              <span>{{ activeSelectionCandidate.symbol }} / {{ activeSelectionCandidate.selectionReason }}</span>
            </div>
            <div class="watch-card-actions">
              <el-button type="primary" plain @click="openCompanyFromWatchlist(activeSelectionCandidate.symbol)">
                查看公司详情
              </el-button>
              <el-button
                plain
                :icon="DocumentChecked"
                :loading="isEvidenceReviewLoading(activeSelectionCandidate.symbol)"
                @click="runEvidenceReview(activeSelectionCandidate.symbol)"
              >
                运行证据复核
              </el-button>
              <el-button
                plain
                :icon="Check"
                :loading="isInvestmentDecisionLoading(activeSelectionCandidate.symbol)"
                @click="runInvestmentDecision(activeSelectionCandidate.symbol)"
              >
                投资门禁
              </el-button>
              <el-tag type="success" effect="plain">{{ activeSelectionCandidate.discussion.consensusLabel }}</el-tag>
            </div>
          </div>

          <div class="trace-list">
            <article v-for="step in activeSelectionCandidate.trace" :key="step.stepCode" class="trace-row">
              <div>
                <strong>{{ step.actor }}</strong>
                <span>{{ step.conclusion }}</span>
              </div>
              <p v-if="step.evidenceRefs.length > 0">{{ step.evidenceRefs.join(' / ') }}</p>
            </article>
          </div>

          <div class="vote-strip">
            <article>
              <span>支持</span>
              <strong>{{ activeSelectionCandidate.discussion.supportCount }}</strong>
            </article>
            <article>
              <span>观察</span>
              <strong>{{ activeSelectionCandidate.discussion.watchCount }}</strong>
            </article>
            <article>
              <span>复核</span>
              <strong>{{ activeSelectionCandidate.discussion.reviewCount }}</strong>
            </article>
            <article class="danger">
              <span>否决</span>
              <strong>{{ activeSelectionCandidate.discussion.vetoCount }}</strong>
            </article>
          </div>

          <div class="agent-opinion-list">
            <article
              v-for="opinion in activeSelectionCandidate.discussion.opinions"
              :key="opinion.agentCode"
              :class="['agent-opinion-row', opinion.vote.toLowerCase()]"
            >
              <div class="agent-opinion-head">
                <div>
                  <strong>{{ opinion.agentName }}</strong>
                  <span>{{ opinion.perspective }}</span>
                </div>
                <b>{{ opinion.voteLabel }} / {{ formatScore(opinion.score) }}</b>
              </div>
              <p v-if="opinion.supports.length > 0">支持：{{ opinion.supports.slice(0, 2).join(' / ') }}</p>
              <p v-if="opinion.objections.length > 0">反对：{{ opinion.objections.slice(0, 2).join(' / ') }}</p>
              <div v-if="opinion.evidenceChecks.length > 0" class="evidence-check-list">
                <article
                  v-for="check in opinion.evidenceChecks"
                  :key="`${opinion.agentCode}-${check.requirement}`"
                  :class="['evidence-check-row', check.status.toLowerCase()]"
                >
                  <div>
                    <strong>{{ check.requirement }}</strong>
                    <span>{{ check.source }} / {{ check.evidenceText }}</span>
                  </div>
                  <el-tag size="small" :type="evidenceStatusType(check.status)" effect="plain">
                    {{ check.statusLabel }}
                  </el-tag>
                </article>
              </div>
            </article>
          </div>

          <div v-if="activeSelectionCandidate.discussion.requiredEvidence.length > 0" class="signal-list">
            <strong>补证清单</strong>
            <span>{{ activeSelectionCandidate.discussion.requiredEvidence.slice(0, 6).join(' / ') }}</span>
          </div>
          <div v-if="activeSelectionEvidenceReview" class="evidence-review-block">
            <div class="evidence-review-head">
              <div>
                <strong>{{ activeSelectionEvidenceReview.reviewLabel }}</strong>
                <span>{{ activeSelectionEvidenceReview.conclusions.join(' / ') }}</span>
              </div>
              <el-tag :type="reviewStatusType(activeSelectionEvidenceReview.reviewStage)" effect="plain">
                {{ activeSelectionEvidenceReview.totalItems }} 项
              </el-tag>
            </div>
            <div class="review-metric-strip">
              <article>
                <span>已核实</span>
                <strong>{{ activeSelectionEvidenceReview.verifiedCount }}</strong>
              </article>
              <article>
                <span>部分补到</span>
                <strong>{{ activeSelectionEvidenceReview.partialCount }}</strong>
              </article>
              <article class="danger">
                <span>未命中</span>
                <strong>{{ activeSelectionEvidenceReview.notFoundCount }}</strong>
              </article>
              <article>
                <span>源阻塞</span>
                <strong>{{ activeSelectionEvidenceReview.blockedCount }}</strong>
              </article>
            </div>
            <div class="review-step-list">
              <article v-for="step in activeSelectionEvidenceReview.steps" :key="step.stepCode" class="review-step-row">
                <strong>{{ step.actor }}</strong>
                <span>{{ step.conclusion }}</span>
                <p v-if="step.evidenceRefs.length > 0">{{ step.evidenceRefs.join(' / ') }}</p>
              </article>
            </div>
            <div class="review-item-list">
              <article
                v-for="item in activeSelectionEvidenceReview.items"
                :key="`${item.agentCode}-${item.requirement}`"
                :class="['review-item-row', item.reviewStatus.toLowerCase()]"
              >
                <div>
                  <strong>{{ item.agentName }} / {{ item.requirement }}</strong>
                  <span>{{ item.searchScope }} / {{ item.source }} / {{ item.evidenceText }}</span>
                  <p>{{ item.verdict }}；下一步：{{ item.nextAction }}</p>
                </div>
                <el-tag size="small" :type="reviewStatusType(item.reviewStatus)" effect="plain">
                  {{ item.reviewStatusLabel }}
                </el-tag>
              </article>
            </div>
          </div>
          <div v-if="activeSelectionInvestmentDecision" class="decision-block">
            <div class="decision-head">
              <div>
                <strong>{{ activeSelectionInvestmentDecision.actionLabel }}</strong>
                <span>{{ activeSelectionInvestmentDecision.actionReason }}</span>
              </div>
              <el-tag :type="decisionStatusType(activeSelectionInvestmentDecision.actionStage)" effect="plain">
                {{ formatScore(activeSelectionInvestmentDecision.decisionScore) }}
              </el-tag>
            </div>
            <p class="decision-note">{{ activeSelectionInvestmentDecision.complianceNote }}</p>
            <div class="decision-metric-strip">
              <article>
                <span>通过</span>
                <strong>{{ activeSelectionInvestmentDecision.passCount }}</strong>
              </article>
              <article>
                <span>观察</span>
                <strong>{{ activeSelectionInvestmentDecision.watchCount }}</strong>
              </article>
              <article class="danger">
                <span>阻断</span>
                <strong>{{ activeSelectionInvestmentDecision.blockCount }}</strong>
              </article>
              <article class="danger">
                <span>失败</span>
                <strong>{{ activeSelectionInvestmentDecision.failCount }}</strong>
              </article>
            </div>
            <div class="financial-history-strip">
              <article>
                <span>财务序列</span>
                <strong>{{ activeSelectionInvestmentDecision.financialHistory.statusLabel }}</strong>
                <p>{{ activeSelectionInvestmentDecision.financialHistory.annualPointCount }} 个年度样本 / 质量分 {{ formatScore(activeSelectionInvestmentDecision.financialHistory.qualityScore) }}</p>
              </article>
              <article>
                <span>平均 ROE</span>
                <strong>{{ formatRatioPercent(activeSelectionInvestmentDecision.financialHistory.averageRoe) }}</strong>
                <p>平均毛利率 {{ formatRatioPercent(activeSelectionInvestmentDecision.financialHistory.averageGrossMargin) }}</p>
              </article>
              <article>
                <span>现金流</span>
                <strong>{{ activeSelectionInvestmentDecision.financialHistory.positiveCashFlowYears }} 年为正</strong>
                <p>营收同比为负 {{ activeSelectionInvestmentDecision.financialHistory.negativeRevenueGrowthYears }} 年</p>
              </article>
              <article>
                <span>数据缺口</span>
                <strong>{{ activeSelectionInvestmentDecision.financialHistory.dataGaps.length }}</strong>
                <p>{{ activeSelectionInvestmentDecision.financialHistory.dataGaps.slice(0, 2).join(' / ') }}</p>
              </article>
            </div>
            <div class="valuation-history-strip">
              <article>
                <span>估值序列</span>
                <strong>{{ activeSelectionInvestmentDecision.valuationHistory.statusLabel }}</strong>
                <p>{{ activeSelectionInvestmentDecision.valuationHistory.sampleCount }} 个年度样本</p>
              </article>
              <article>
                <span>当前估值</span>
                <strong>PE {{ formatNumber(activeSelectionInvestmentDecision.valuationHistory.currentPe) }}</strong>
                <p>PB {{ formatNumber(activeSelectionInvestmentDecision.valuationHistory.currentPb) }}</p>
              </article>
              <article>
                <span>历史分位</span>
                <strong>PE {{ formatRatioPercent(activeSelectionInvestmentDecision.valuationHistory.pePercentile) }}</strong>
                <p>PB {{ formatRatioPercent(activeSelectionInvestmentDecision.valuationHistory.pbPercentile) }}</p>
              </article>
              <article>
                <span>样本口径</span>
                <strong>{{ activeSelectionInvestmentDecision.valuationHistory.dataGaps.length }}</strong>
                <p>{{ activeSelectionInvestmentDecision.valuationHistory.dataGaps.slice(0, 2).join(' / ') || '暂无缺口' }}</p>
              </article>
            </div>
            <div class="peer-valuation-strip">
              <article>
                <span>同业估值</span>
                <strong>{{ activeSelectionInvestmentDecision.valuationHistory.peerValuation.scopeLabel }}</strong>
                <p>{{ activeSelectionInvestmentDecision.valuationHistory.peerValuation.peerCount }} 个可比样本</p>
              </article>
              <article>
                <span>行业中位数</span>
                <strong>PE {{ formatNumber(activeSelectionInvestmentDecision.valuationHistory.peerValuation.medianPe) }}</strong>
                <p>PB {{ formatNumber(activeSelectionInvestmentDecision.valuationHistory.peerValuation.medianPb) }}</p>
              </article>
              <article>
                <span>同业分位</span>
                <strong>PE {{ formatRatioPercent(activeSelectionInvestmentDecision.valuationHistory.peerValuation.pePeerPercentile) }}</strong>
                <p>PB {{ formatRatioPercent(activeSelectionInvestmentDecision.valuationHistory.peerValuation.pbPeerPercentile) }}</p>
              </article>
              <article>
                <span>更便宜样本</span>
                <strong>{{ activeSelectionInvestmentDecision.valuationHistory.peerValuation.cheaperPeCount }} / {{ activeSelectionInvestmentDecision.valuationHistory.peerValuation.cheaperPbCount }}</strong>
                <p>{{ activeSelectionInvestmentDecision.valuationHistory.peerValuation.dataGaps.slice(0, 1).join(' / ') || '横向样本可用' }}</p>
              </article>
            </div>
            <div class="decision-gate-list">
              <article
                v-for="gate in activeSelectionInvestmentDecision.gates"
                :key="gate.gateCode"
                :class="['decision-gate-row', gate.status.toLowerCase()]"
              >
                <div>
                  <strong>{{ gate.gateName }}</strong>
                  <span>{{ gate.conclusion }}</span>
                  <p v-if="gate.evidenceRefs.length > 0">{{ gate.evidenceRefs.join(' / ') }}</p>
                </div>
                <el-tag size="small" :type="decisionStatusType(gate.status)" effect="plain">
                  {{ gate.statusLabel }}
                </el-tag>
              </article>
            </div>
            <div class="decision-section-grid">
              <article>
                <strong>长期假设</strong>
                <span>{{ activeSelectionInvestmentDecision.thesis.join(' / ') }}</span>
              </article>
              <article>
                <strong>买入前条件</strong>
                <span>{{ activeSelectionInvestmentDecision.buyPreconditions.join(' / ') }}</span>
              </article>
              <article>
                <strong>持有纪律</strong>
                <span>{{ activeSelectionInvestmentDecision.holdDisciplines.join(' / ') }}</span>
              </article>
              <article>
                <strong>下一步动作</strong>
                <span>{{ activeSelectionInvestmentDecision.requiredActions.join(' / ') }}</span>
              </article>
            </div>
            <div class="exit-trigger-list">
              <article v-for="trigger in activeSelectionInvestmentDecision.exitTriggers" :key="trigger.triggerCode">
                <div>
                  <strong>{{ trigger.triggerName }}</strong>
                  <span>{{ trigger.condition }}</span>
                  <p>{{ trigger.action }}</p>
                </div>
                <el-tag size="small" :type="triggerSeverityType(trigger.severity)" effect="plain">
                  {{ trigger.severity }}
                </el-tag>
              </article>
            </div>
          </div>
        </article>
      </section>

      <el-empty v-else class="panel empty-panel" description="还没有生成 Agent 选股会结果">
        <el-button type="primary" :loading="selectionLoading" @click="loadAgentSelection">开始生成</el-button>
      </el-empty>
    </section>

    <section v-else-if="currentPage === 'watchlist'" class="page-stack">
      <article class="panel section-banner">
        <div>
          <p class="eyebrow">WATCHLIST</p>
          <h2>长线观察池</h2>
        </div>
        <p>观察池按推荐指数降序排列，第一页默认展示当前优先跟踪的候选标的。</p>
      </article>

      <section class="watch-grid">
        <article v-for="entry in pagedWatchlist" :key="entry.symbol" class="panel watch-card">
          <div class="watch-card-head">
            <div>
              <p class="eyebrow">RANK {{ watchlistRank(entry.symbol) }}</p>
              <h2>{{ entry.companyName }}</h2>
              <span>{{ entry.symbol }} / {{ entry.decision }} / {{ entry.researchStageLabel }}</span>
            </div>
            <div class="feature-score compact">
              <span>推荐指数</span>
              <strong>{{ formatScore(watchlistRecommendation(entry)) }}</strong>
            </div>
          </div>

          <p class="watch-card-copy">{{ entry.thesis }}</p>

          <div class="tag-line">
            <el-tag v-for="item in entry.nextChecks.slice(0, 3)" :key="item" effect="plain">{{ item }}</el-tag>
          </div>

          <div class="watch-card-actions">
            <el-button type="primary" plain @click="openCompanyFromWatchlist(entry.symbol)">查看公司详情</el-button>
            <el-tag type="success" effect="plain">{{ entry.researchStageLabel }}</el-tag>
            <el-tag effect="plain">{{ entry.ruleVersion }}</el-tag>
          </div>
        </article>
      </section>

      <div class="panel pagination-panel">
        <el-pagination
          v-model:current-page="watchlistPager.page"
          v-model:page-size="watchlistPager.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[6, 9, 12]"
          :total="sortedWatchlist.length"
          @size-change="watchlistPager.page = 1"
        />
      </div>
    </section>

    <section v-else class="settings-layout">
      <div class="panel settings-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">MODEL PROVIDER</p>
            <h2>大模型配置</h2>
          </div>
          <div class="ai-config-tags">
            <el-tag :type="runtimeConfigForm.llm.apiKeyConfigured ? 'success' : 'danger'" effect="plain">
              {{ runtimeConfigForm.llm.apiKeyConfigured ? 'Key 已配置' : 'Key 缺失' }}
            </el-tag>
            <el-tag effect="plain">数据库配置 · 模型修订 {{ runtimeConfigForm.llmRevision }}</el-tag>
          </div>
        </div>

        <el-form label-position="top" class="settings-form">
          <div class="form-grid">
            <el-form-item label="Provider">
              <el-select v-model="runtimeConfigForm.llm.provider" class="full-width" @change="changeLlmProvider">
                <el-option label="DeepSeek" value="deepseek" />
                <el-option label="OpenAI" value="openai" />
                <el-option label="Moonshot / Kimi 开放平台" value="moonshot" />
                <el-option label="Kimi Code" value="kimi-code" />
              </el-select>
            </el-form-item>
            <el-form-item label="模型">
              <el-input v-model="runtimeConfigForm.llm.model" />
            </el-form-item>
            <el-form-item label="Base URL">
              <el-input v-model="runtimeConfigForm.llm.baseUrl" />
            </el-form-item>
            <el-form-item label="Response Format">
              <el-select v-model="runtimeConfigForm.llm.responseFormat" class="full-width">
                <el-option label="json_object" value="json_object" />
                <el-option label="json_schema" value="json_schema" />
                <el-option label="none" value="none" />
              </el-select>
            </el-form-item>
            <el-form-item label="API Key">
              <el-input
                v-model="runtimeConfigForm.llm.apiKey"
                type="password"
                show-password
                placeholder="留空则保留数据库中已有 Key"
              />
            </el-form-item>
            <el-form-item label="API Key 环境变量名">
              <el-input v-model="runtimeConfigForm.llm.apiKeyEnv" placeholder="例如 DEEPSEEK_API_KEY" />
            </el-form-item>
            <el-form-item label="最大输出 Token">
              <el-input-number v-model="runtimeConfigForm.llm.maxCompletionTokens" :min="1" :max="200000" class="full-width" />
            </el-form-item>
            <el-form-item label="Temperature">
              <el-input-number
                v-model="runtimeConfigForm.llm.temperature"
                :min="0"
                :max="2"
                :step="0.1"
                class="full-width"
              />
            </el-form-item>
          </div>
          <div class="settings-switches">
            <el-switch v-model="runtimeConfigForm.llm.strictJsonSchema" active-text="严格 JSON Schema" />
            <el-input v-model="runtimeConfigForm.llm.thinking" class="thinking-input" placeholder="thinking 类型，可留空" />
          </div>
        </el-form>

        <div class="settings-actions">
          <el-button :icon="Refresh" :loading="llmRuntimeConfigLoading" @click="loadLlmRuntimeConfig">重新读取</el-button>
          <el-button type="primary" :icon="Check" :loading="llmRuntimeConfigSaving" @click="saveLlmRuntimeConfig">
            保存配置
          </el-button>
        </div>
      </div>

      <div class="panel settings-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">POLICY SOURCES</p>
            <h2>政策源配置</h2>
          </div>
          <div class="ai-config-tags">
            <el-tag effect="plain">数据库配置 · 政策源修订 {{ runtimeConfigForm.policySourcesRevision }}</el-tag>
            <el-button :icon="Plus" plain @click="addPolicySource">新增来源</el-button>
          </div>
        </div>

        <div class="source-editor">
          <article v-for="(source, index) in runtimeConfigForm.policySources" :key="`${source.name}-${index}`" class="source-row">
            <div class="source-index">{{ index + 1 }}</div>
            <el-input v-model="source.name" placeholder="来源名称" />
            <el-select v-model="source.type" class="source-type">
              <el-option label="HTML" value="html" />
              <el-option label="JSON" value="json" />
            </el-select>
            <el-input v-model="source.url" class="source-url" placeholder="政策源 URL" />
            <el-input-number v-model="source.weight" :min="1" :max="100" class="source-weight" />
            <el-tooltip content="删除来源">
              <el-button :icon="Delete" circle plain type="danger" @click="removePolicySource(index)" />
            </el-tooltip>
          </article>
        </div>

        <div class="settings-actions">
          <el-button :icon="Refresh" :loading="policySourcesLoading" @click="loadPolicySources">重新读取</el-button>
          <el-button type="primary" :icon="Check" :loading="policySourcesSaving" @click="savePolicySources">
            保存配置
          </el-button>
        </div>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import {
  Check,
  Cpu,
  Delete,
  DocumentChecked,
  MagicStick,
  Plus,
  Refresh,
  Search,
  TrendCharts
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  analyzeTrend,
  enhanceCompanyConsensus,
  evaluateRules,
  fetchAgentShortlist,
  fetchCompanies,
  fetchCompanyConsensus,
  fetchEvidenceReview,
  fetchCompanyResearch,
  fetchInvestmentDecision,
  fetchLatestTrendAnalysis,
  fetchLlmConfig,
  fetchLlmRuntimeConfig,
  fetchPolicyThemes,
  fetchPolicySources,
  fetchRules,
  fetchRuntimeConfig,
  fetchTrendAnalysisHistory,
  fetchWatchlist,
  previewTrendPrompt,
  updateLlmRuntimeConfig,
  updatePolicySources
} from './api/client'
import type {
  AgentConsensusReport,
  ApiErrorBody,
  CompanyProfile,
  CompanyResearchView,
  EvidenceReviewReport,
  InvestmentDecisionReport,
  JsonValue,
  LlmConfigPreview,
  PolicyTheme,
  RuleDefinition,
  RuleEvaluationResult,
  RuntimeConfigSnapshot,
  TrendAnalysisHistoryItem,
  TrendAnalysisResponse,
  TrendPromptPreview,
  TrendPromptRequest,
  StockSelectionCandidate,
  StockSelectionReport,
  WatchlistEntry
} from './types'

use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

type PageKey = 'overview' | 'ai' | 'policy' | 'companies' | 'rules' | 'selection' | 'watchlist' | 'settings'

interface TrendDailyCache {
  day: string
  form: TrendPromptRequest
  promptPreview: TrendPromptPreview | null
  analysis: TrendAnalysisResponse | null
}

const TREND_DAILY_CACHE_KEY = 'aistock-trend-daily-cache-v1'

const pageMeta: Record<PageKey, { eyebrow: string; title: string; description: string }> = {
  overview: {
    eyebrow: 'AI STOCK RESEARCH',
    title: '长线投研总览',
    description: '把政策、公司、规则和观察池拆成独立模块后，这里只保留全局概览和最高优先级样本。'
  },
  ai: {
    eyebrow: 'AI WORKFLOW',
    title: '趋势分析工作台',
    description: '用结构化提示词把政策与产业材料转成可审计的趋势结论。'
  },
  policy: {
    eyebrow: 'POLICY RADAR',
    title: '政策主题列表',
    description: '主题页专门展示推荐指数排序后的政策主线，第一页就是最值得看的方向。'
  },
  companies: {
    eyebrow: 'COMPANY POOL',
    title: '主题公司池',
    description: '公司页统一按推荐指数排序，并保留证据链、规则试算和观察池联动。'
  },
  rules: {
    eyebrow: 'RULE ENGINE',
    title: '规则目录',
    description: '把规则从主页面抽出来，便于单独查看优先级、动作和版本。'
  },
  selection: {
    eyebrow: 'AGENT MEETING',
    title: 'Agent 选股会',
    description: '全市场候选由不同 Agent 共同讨论，输出少量可追溯的长线观察标的。'
  },
  watchlist: {
    eyebrow: 'WATCHLIST',
    title: '长线观察池',
    description: '观察池默认按推荐指数降序排列，第一页就是当前最该跟踪的标的。'
  },
  settings: {
    eyebrow: 'RUNTIME CONFIG',
    title: '系统配置',
    description: '模型和政策源配置保留在独立页面，避免和投研主界面混在一起。'
  }
}

const loading = ref(false)
const routeHash = ref(window.location.hash || '#/overview')
const themes = ref<PolicyTheme[]>([])
const companies = ref<CompanyProfile[]>([])
const rules = ref<RuleDefinition[]>([])
const watchlist = ref<WatchlistEntry[]>([])
const evaluations = ref<RuleEvaluationResult[]>([])
const activeResearch = ref<CompanyResearchView | null>(null)
const activeConsensus = ref<AgentConsensusReport | null>(null)
const activeEvidenceReview = ref<EvidenceReviewReport | null>(null)
const activeInvestmentDecision = ref<InvestmentDecisionReport | null>(null)
const selectedSymbol = ref('')
const llmConfig = ref<LlmConfigPreview | null>(null)
const trendPromptPreview = ref<TrendPromptPreview | null>(null)
const trendAnalysis = ref<TrendAnalysisResponse | null>(null)
const trendHistory = ref<TrendAnalysisHistoryItem[]>([])
const selectionReport = ref<StockSelectionReport | null>(null)
const activeSelectionSymbol = ref('')
const aiError = ref('')
const configLoading = ref(false)
const previewLoading = ref(false)
const aiLoading = ref(false)
const runtimeConfigLoading = ref(false)
const llmRuntimeConfigLoading = ref(false)
const llmRuntimeConfigSaving = ref(false)
const policySourcesLoading = ref(false)
const policySourcesSaving = ref(false)
const researchLoading = ref(false)
const consensusLoading = ref(false)
const aiConsensusLoading = ref(false)
const evidenceReviewLoading = ref(false)
const evidenceReviewSymbol = ref('')
const investmentDecisionLoading = ref(false)
const investmentDecisionSymbol = ref('')
const selectionLoading = ref(false)
const runtimeConfig = ref<RuntimeConfigSnapshot | null>(null)
const runtimeConfigForm = ref<RuntimeConfigSnapshot>(emptyRuntimeConfig())
const trendFormSeeded = ref(false)
const trendForm = reactive<TrendPromptRequest>({
  documentTitle: '国务院关于印发《现代化应急体系建设“十五五”规划》的通知',
  documentType: '政府规划文件',
  sourceOrganization: '国务院',
  publishedAt: '2026-06-08',
  sourceUrl: 'https://www.gov.cn/zhengce/content/202606/content_7071451.htm',
  contentExcerpt:
    '围绕现代化应急体系建设，强调安全韧性、数字化支撑、先进适用装备、基层应急能力、监测预警、标准体系和产业支撑。',
  focusThemes: ['新质生产力', '高端制造', '数字基础设施', '公共安全'],
  knownCompanies: ['浪潮信息', '东方电子', '中际旭创']
})
const themeChartRef = ref<HTMLElement | null>(null)
const policyPager = reactive({ page: 1, pageSize: 6 })
const companyPager = reactive({ page: 1, pageSize: 10 })
const rulePager = reactive({ page: 1, pageSize: 8 })
const watchlistPager = reactive({ page: 1, pageSize: 6 })
let themeChart: ECharts | null = null

const currentPage = computed<PageKey>(() => parseRoute(routeHash.value))
const currentPageMeta = computed(() => pageMeta[currentPage.value])
const totalRuleVersions = computed(() => rules.value.reduce((sum, rule) => sum + rule.version, 0))
const liveCompanyCount = computed(() => companies.value.filter((company) => company.liveData).length)
const themeOptions = computed(() => Array.from(new Set(themes.value.flatMap((theme) => [theme.name, ...theme.chainSegments]))))
const companyOptions = computed(() => sortedCompanies.value.map((company) => company.name))
const llmProviderTagType = computed(() => {
  if (!llmConfig.value?.apiKeyConfigured) return 'danger'
  if (llmConfig.value.provider === 'kimi-code') return 'warning'
  if (llmConfig.value.provider === 'moonshot') return 'success'
  return 'info'
})
const llmConfigLabel = computed(() => {
  if (!llmConfig.value) return '模型未读取'
  return `${llmConfig.value.provider} / ${llmConfig.value.model}`
})
const watchlistScoreMap = computed(() => new Map(watchlist.value.map((entry) => [entry.symbol, Number(entry.score)])))
const sortedThemes = computed(() => [...themes.value].sort((a, b) => policyRecommendation(b) - policyRecommendation(a)))
const sortedCompanies = computed(() =>
  [...companies.value].sort((a, b) => companyRecommendation(b) - companyRecommendation(a) || Number(b.themeRelevance) - Number(a.themeRelevance))
)
const sortedRules = computed(() => [...rules.value].sort((a, b) => rulePriority(b) - rulePriority(a) || b.version - a.version))
const sortedWatchlist = computed(() => [...watchlist.value].sort((a, b) => watchlistRecommendation(b) - watchlistRecommendation(a)))
const pagedThemes = computed(() => paginate(sortedThemes.value, policyPager))
const pagedCompanies = computed(() => paginate(sortedCompanies.value, companyPager))
const pagedRules = computed(() => paginate(sortedRules.value, rulePager))
const pagedWatchlist = computed(() => paginate(sortedWatchlist.value, watchlistPager))
const topThemes = computed(() => sortedThemes.value.slice(0, 4))
const topCompany = computed(() => sortedCompanies.value[0] ?? null)
const topWatchlist = computed(() => sortedWatchlist.value.slice(0, 4))
const activeSelectionCandidate = computed<StockSelectionCandidate | null>(() => {
  const candidates = selectionReport.value?.candidates ?? []
  if (candidates.length === 0) return null
  return candidates.find((candidate) => candidate.symbol === activeSelectionSymbol.value) ?? candidates[0]
})
const activeCompanyEvidenceReview = computed(() =>
  activeEvidenceReview.value?.symbol === activeCompany.value?.symbol ? activeEvidenceReview.value : null
)
const activeSelectionEvidenceReview = computed(() =>
  activeEvidenceReview.value?.symbol === activeSelectionCandidate.value?.symbol ? activeEvidenceReview.value : null
)
const activeCompanyInvestmentDecision = computed(() =>
  activeInvestmentDecision.value?.symbol === activeCompany.value?.symbol ? activeInvestmentDecision.value : null
)
const activeSelectionInvestmentDecision = computed(() =>
  activeInvestmentDecision.value?.symbol === activeSelectionCandidate.value?.symbol ? activeInvestmentDecision.value : null
)
const activeCompany = computed(() => companies.value.find((company) => company.symbol === selectedSymbol.value) ?? sortedCompanies.value[0] ?? null)
const relatedWatchlist = computed(() => {
  if (!activeCompany.value) return sortedWatchlist.value.slice(0, 4)
  const matched = sortedWatchlist.value.filter((entry) => entry.symbol === activeCompany.value?.symbol)
  return matched.length > 0 ? matched : sortedWatchlist.value.slice(0, 4)
})
const navigationItems = computed(() => [
  { key: 'overview' as PageKey, label: '总览', count: `${watchlist.value.length} 候选` },
  { key: 'ai' as PageKey, label: 'AI 分析', count: trendAnalysis.value ? '已生成' : '待运行' },
  { key: 'policy' as PageKey, label: '政策主题', count: `${themes.value.length} 条` },
  { key: 'companies' as PageKey, label: '公司池', count: `${companies.value.length} 家` },
  { key: 'rules' as PageKey, label: '规则目录', count: `${rules.value.length} 条` },
  { key: 'selection' as PageKey, label: 'Agent 选股会', count: selectionReport.value ? `${selectionReport.value.selectedCount} 只` : '待生成' },
  { key: 'watchlist' as PageKey, label: '观察池', count: `${watchlist.value.length} 条` },
  { key: 'settings' as PageKey, label: '配置', count: runtimeConfig.value ? '已加载' : '未读取' }
])
const analysis = computed(() => trendAnalysis.value?.analysis ?? null)
const overallAssessment = computed(() => asRecord(analysis.value?.overall_assessment))
const overallSummary = computed(() => textValue(overallAssessment.value?.summary, '等待 AI 分析结果'))
const overallNextAction = computed(() => textValue(overallAssessment.value?.next_action, '待判断'))
const overallConfidence = computed(() => textValue(overallAssessment.value?.confidence, '待补充'))
const trendAnalysisStatus = computed(() => {
  if (!trendAnalysis.value) return '等待分析'
  return trendAnalysis.value.cached ? '已复用今日归档结果' : '本次新生成并已归档'
})
const hiddenTrendCards = computed(() =>
  asArray(analysis.value?.hidden_trends).map((item, index) => {
    const record = asRecord(item)
    return {
      name: textValue(record?.trend_name, `趋势 ${index + 1}`),
      type: textValue(record?.trend_type, '趋势类型待补充'),
      strength: textValue(record?.trend_strength, '0'),
      evidenceStrength: textValue(record?.evidence_strength, '0'),
      logicChain: stringList(record?.logic_chain),
      profiles: stringList(record?.beneficiary_profiles)
    }
  })
)
const monitoringCards = computed(() =>
  asArray(analysis.value?.monitoring_indicators).map((item) => {
    const record = asRecord(item)
    return {
      indicator: textValue(record?.indicator, '监控指标待补充'),
      direction: textValue(record?.direction_to_watch, '方向待补充'),
      source: textValue(record?.data_source_hint, '数据源待补充')
    }
  })
)
const counterEvidenceCards = computed(() =>
  asArray(analysis.value?.counter_evidence).map((item) => {
    const record = asRecord(item)
    return {
      condition: textValue(record?.condition, '反证条件待补充'),
      severity: textValue(record?.severity, 'LOW'),
      impact: textValue(record?.impact, '影响待补充')
    }
  })
)
const promptPreviewExcerpt = computed(() => {
  const prompt = trendPromptPreview.value?.userPrompt ?? ''
  return prompt.length > 360 ? `${prompt.slice(0, 360)}...` : prompt
})
const activeCompanyAnalysis = computed(() => {
  const company = activeCompany.value
  if (!company) {
    return {
      verdict: '选择一家公司后显示分析',
      valuation: '',
      quality: '',
      nextChecks: []
    }
  }
  const research = activeResearch.value?.company.symbol === company.symbol ? activeResearch.value : null
  if (research) {
    const valuation = research.dimensions.find((dimension) => dimension.code === 'VALUATION')
    const quality = research.dimensions.find((dimension) => dimension.code === 'QUALITY')
    return {
      verdict: `${research.stageLabel}：${research.stageReason}`,
      valuation: valuation ? `估值侧：${valuation.verdict}，评分 ${formatScore(valuation.score)}。` : '',
      quality: quality ? `质量侧：${quality.verdict}，评分 ${formatScore(quality.score)}。` : '',
      nextChecks: research.nextActions
    }
  }
  const relevance = Number(company.themeRelevance ?? 0)
  const verdict = relevance >= 84
    ? '主题相关度较高，适合进入长线候选池复核'
    : relevance >= 70
      ? '主题相关度中等，需要主营和订单证据补强'
      : '主题命中较弱，建议先作为观察样本'
  const pe = company.peTtm
  const pb = company.pbRatio
  const valuation = pe == null || pe <= 0
    ? '估值侧：PE 缺失或为负，盈利稳定性需要优先确认。'
    : pe > 80
      ? `估值侧：PE(TTM) ${formatNumber(pe)}，市场预期较满，需要更强订单和现金流验证。`
      : `估值侧：PE(TTM) ${formatNumber(pe)} / PB ${formatNumber(pb)}，可进入估值分位和同业比较。`
  const quality = company.financialReportDate
    ? `质量侧：已匹配 ${company.financialDataType ?? '年报指标'}，可结合 ROE、现金流和毛利率继续过滤。`
    : '质量侧：当前未匹配最近年度年报指标，后续必须读取年报与主营构成。'
  const nextChecks = [
    '核查主营收入中与政策主题直接相关的业务占比',
    '读取最近三年年报中的研发投入、现金流和毛利率趋势',
    '检查重大合同、招投标、中标公告和产能建设进度',
    '排查监管处罚、质押、商誉减值和应收账款异常'
  ]
  return { verdict, valuation, quality, nextChecks }
})

function emptyRuntimeConfig(): RuntimeConfigSnapshot {
  return {
    storage: 'database',
    llmRevision: 0,
    policySourcesRevision: 0,
    llm: {
      provider: 'deepseek',
      apiKey: '',
      apiKeyEnv: 'DEEPSEEK_API_KEY',
      model: 'deepseek-v4-pro',
      baseUrl: 'https://api.deepseek.com',
      responseFormat: 'json_object',
      strictJsonSchema: false,
      thinking: null,
      maxCompletionTokens: 8192,
      temperature: null,
      apiKeyConfigured: false,
      apiKeySource: 'missing'
    },
    policySources: [],
    updatedAt: new Date().toISOString()
  }
}

const LLM_PROVIDER_DEFAULTS: Record<string, {
  model: string
  baseUrl: string
  apiKeyEnv: string
  responseFormat: string
  maxCompletionTokens: number | null
}> = {
  deepseek: {
    model: 'deepseek-v4-pro',
    baseUrl: 'https://api.deepseek.com',
    apiKeyEnv: 'DEEPSEEK_API_KEY',
    responseFormat: 'json_object',
    maxCompletionTokens: 8192
  },
  openai: {
    model: 'gpt-5.5',
    baseUrl: 'https://api.openai.com/v1',
    apiKeyEnv: 'OPENAI_API_KEY',
    responseFormat: 'json_schema',
    maxCompletionTokens: null
  },
  moonshot: {
    model: 'kimi-k2.6',
    baseUrl: 'https://api.moonshot.ai/v1',
    apiKeyEnv: 'MOONSHOT_API_KEY',
    responseFormat: 'json_schema',
    maxCompletionTokens: null
  },
  'kimi-code': {
    model: 'kimi-for-coding',
    baseUrl: 'https://api.kimi.com/coding/v1',
    apiKeyEnv: 'KIMI_API_KEY',
    responseFormat: 'json_schema',
    maxCompletionTokens: null
  }
}

function currentDayStamp() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function cloneTrendFormValue(value: TrendPromptRequest): TrendPromptRequest {
  return {
    ...value,
    focusThemes: [...value.focusThemes],
    knownCompanies: [...value.knownCompanies]
  }
}

function applyTrendFormValue(value: TrendPromptRequest) {
  trendForm.documentTitle = value.documentTitle
  trendForm.documentType = value.documentType
  trendForm.sourceOrganization = value.sourceOrganization
  trendForm.publishedAt = value.publishedAt
  trendForm.sourceUrl = value.sourceUrl
  trendForm.contentExcerpt = value.contentExcerpt
  trendForm.focusThemes = [...value.focusThemes]
  trendForm.knownCompanies = [...value.knownCompanies]
}

function saveTrendDailyCache() {
  const payload: TrendDailyCache = {
    day: currentDayStamp(),
    form: cloneTrendFormValue(trendForm),
    promptPreview: trendPromptPreview.value,
    analysis: trendAnalysis.value
  }
  window.localStorage.setItem(TREND_DAILY_CACHE_KEY, JSON.stringify(payload))
}

function restoreTrendDailyCache() {
  const raw = window.localStorage.getItem(TREND_DAILY_CACHE_KEY)
  if (!raw) return
  try {
    const parsed = JSON.parse(raw) as Partial<TrendDailyCache>
    if (parsed.day !== currentDayStamp()) {
      window.localStorage.removeItem(TREND_DAILY_CACHE_KEY)
      return
    }
    if (parsed.form) {
      applyTrendFormValue(parsed.form)
      trendFormSeeded.value = true
    }
    trendPromptPreview.value = parsed.promptPreview ?? null
    trendAnalysis.value = parsed.analysis ?? null
  } catch (_error) {
    window.localStorage.removeItem(TREND_DAILY_CACHE_KEY)
  }
}

function cloneRuntimeConfig(config: RuntimeConfigSnapshot): RuntimeConfigSnapshot {
  return {
    ...config,
    llm: {
      ...config.llm,
      apiKey: ''
    },
    policySources: config.policySources.map((source) => ({ ...source }))
  }
}

function parseRoute(hash: string): PageKey {
  const route = hash.replace(/^#\//, '').trim()
  const page = route === '' ? 'overview' : route
  const validPages: PageKey[] = ['overview', 'ai', 'policy', 'companies', 'rules', 'selection', 'watchlist', 'settings']
  return validPages.includes(page as PageKey) ? (page as PageKey) : 'overview'
}

function syncRouteHash() {
  routeHash.value = window.location.hash || '#/overview'
}

function navigateTo(page: PageKey) {
  window.location.hash = `#/${page}`
}

function policyRecommendation(theme: PolicyTheme) {
  return Number(theme.strengthScore ?? 0)
}

function watchlistRecommendation(entry: WatchlistEntry) {
  return Number(entry.score ?? 0)
}

function companyRecommendation(company: CompanyProfile) {
  const themeScore = Number(company.themeRelevance ?? 0)
  const watchScore = watchlistScoreMap.value.get(company.symbol) ?? 0
  const valuationBonus = company.peTtm != null && company.peTtm > 0 && company.peTtm <= 40 ? 4 : 0
  const valuationPenalty = company.peTtm != null && company.peTtm > 80 ? 6 : 0
  const qualityBonus = company.financialReportDate ? 6 : -8
  const liquidityBonus = company.amount != null && company.amount >= 100000000 ? 4 : 0
  const score = watchScore * 0.65 + themeScore * 0.35 + valuationBonus + qualityBonus + liquidityBonus - valuationPenalty
  return Math.max(0, Math.min(99, Number(score.toFixed(1))))
}

function rulePriority(rule: RuleDefinition) {
  const actionBonus = rule.action === 'REJECT' ? 18 : rule.action === 'SCORE' ? 12 : 8
  const weight = rule.conditions.reduce((sum, condition) => sum + Number(condition.weight ?? 1), 0)
  return Math.min(99, Number((rule.version * 12 + rule.conditions.length * 8 + weight * 3 + actionBonus).toFixed(1)))
}

function paginate<T>(items: T[], pager: { page: number; pageSize: number }) {
  const start = (pager.page - 1) * pager.pageSize
  return items.slice(start, start + pager.pageSize)
}

function rankNumber(page: number, pageSize: number, index: number) {
  return (page - 1) * pageSize + index + 1
}

function watchlistRank(symbol: string) {
  const index = sortedWatchlist.value.findIndex((entry) => entry.symbol === symbol)
  return index >= 0 ? index + 1 : '-'
}

function companyRowClassName({ row }: { row: CompanyProfile }) {
  return row.symbol === selectedSymbol.value ? 'selected-company-row' : ''
}

function actionType(action: string) {
  if (action === 'REJECT') return 'danger'
  if (action === 'SCORE') return 'success'
  if (action === 'REVIEW') return 'info'
  return 'primary'
}

function evidenceStatusType(status: string) {
  if (status === 'FOUND') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'MISSING') return 'danger'
  return 'info'
}

function reviewStatusType(status: string) {
  if (status === 'VERIFIED') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'NOT_FOUND') return 'danger'
  if (status === 'BLOCKED') return 'info'
  if (status === 'UPGRADE_READY' || status === 'CLEAR') return 'success'
  if (status === 'PARTIAL_REVIEWED') return 'warning'
  if (status === 'EVIDENCE_GAP') return 'danger'
  if (status === 'SOURCE_BLOCKED') return 'info'
  return 'info'
}

function decisionStatusType(status: string) {
  if (status === 'PASS' || status === 'PRE_BUY_CHECK') return 'success'
  if (status === 'WATCH' || status === 'WAIT_FOR_PRICE' || status === 'LONG_WATCH') return 'warning'
  if (status === 'BLOCK' || status === 'EVIDENCE_ONLY') return 'danger'
  if (status === 'FAIL' || status === 'RISK_BLOCKED') return 'danger'
  return 'info'
}

function triggerSeverityType(severity: string) {
  if (severity === 'HIGH') return 'danger'
  if (severity === 'MEDIUM') return 'warning'
  return 'info'
}

function isEvidenceReviewLoading(symbol: string | undefined) {
  return evidenceReviewLoading.value && evidenceReviewSymbol.value === symbol
}

function isInvestmentDecisionLoading(symbol: string | undefined) {
  return investmentDecisionLoading.value && investmentDecisionSymbol.value === symbol
}

function actionLabel(action: string) {
  const labels: Record<string, string> = {
    PASS: '通过门槛',
    REJECT: '失败则排除',
    SCORE: '评分',
    ALERT: '预警',
    DOWN_WEIGHT: '降权',
    REVIEW: '人工复核'
  }
  return labels[action] ?? action
}

function selectCompany(company: CompanyProfile) {
  void focusCompany(company.symbol)
}

async function focusCompany(symbol: string) {
  selectedSymbol.value = symbol
  const index = sortedCompanies.value.findIndex((company) => company.symbol === symbol)
  if (index >= 0) {
    companyPager.page = Math.floor(index / companyPager.pageSize) + 1
  }
  await Promise.allSettled([runEvaluation(), loadActiveResearch(symbol), loadActiveConsensus(symbol)])
}

async function loadActiveResearch(symbol = selectedSymbol.value) {
  if (!symbol) {
    activeResearch.value = null
    return
  }
  researchLoading.value = true
  try {
    const result = await fetchCompanyResearch(symbol)
    if (selectedSymbol.value === symbol) {
      activeResearch.value = result
    }
  } catch (_error) {
    if (selectedSymbol.value === symbol) {
      activeResearch.value = null
    }
  } finally {
    if (selectedSymbol.value === symbol) {
      researchLoading.value = false
    }
  }
}

async function loadActiveConsensus(symbol = selectedSymbol.value) {
  if (!symbol) {
    activeConsensus.value = null
    return
  }
  if (activeConsensus.value?.symbol !== symbol) {
    activeConsensus.value = null
  }
  consensusLoading.value = true
  try {
    const result = await fetchCompanyConsensus(symbol)
    if (selectedSymbol.value === symbol) {
      activeConsensus.value = result
    }
  } catch (_error) {
    if (selectedSymbol.value === symbol) {
      activeConsensus.value = null
    }
  } finally {
    if (selectedSymbol.value === symbol) {
      consensusLoading.value = false
    }
  }
}

async function runAiConsensus(symbol = selectedSymbol.value) {
  if (!symbol) {
    return
  }
  aiConsensusLoading.value = true
  try {
    const result = await enhanceCompanyConsensus(symbol)
    if (selectedSymbol.value === symbol) {
      activeConsensus.value = result
      if (result.aiEnhanced) {
        ElMessage.success('AI 辩论增强已完成')
      } else if (result.aiWarnings.length > 0) {
        ElMessage.warning(result.aiWarnings[0])
      }
    }
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    if (selectedSymbol.value === symbol) {
      aiConsensusLoading.value = false
    }
  }
}

async function runEvidenceReview(symbol = selectedSymbol.value) {
  if (!symbol) {
    return
  }
  evidenceReviewSymbol.value = symbol
  evidenceReviewLoading.value = true
  try {
    const result = await fetchEvidenceReview(symbol)
    activeEvidenceReview.value = result
    ElMessage.success('证据复核链条已跑通')
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    if (evidenceReviewSymbol.value === symbol) {
      evidenceReviewLoading.value = false
    }
  }
}

async function runInvestmentDecision(symbol = selectedSymbol.value) {
  if (!symbol) {
    return
  }
  investmentDecisionSymbol.value = symbol
  investmentDecisionLoading.value = true
  try {
    const result = await fetchInvestmentDecision(symbol)
    activeInvestmentDecision.value = result
    activeEvidenceReview.value = result.evidenceReview
    if (selectedSymbol.value === symbol) {
      activeConsensus.value = result.consensus
    }
    ElMessage.success('投资门禁评估已完成')
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    if (investmentDecisionSymbol.value === symbol) {
      investmentDecisionLoading.value = false
    }
  }
}

async function loadAgentSelection() {
  selectionLoading.value = true
  try {
    const result = await fetchAgentShortlist(5, 18)
    selectionReport.value = result
    activeSelectionSymbol.value = result.candidates[0]?.symbol ?? ''
    if (result.candidates.length > 0) {
      ElMessage.success('Agent 选股会已生成')
    }
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    selectionLoading.value = false
  }
}

function selectSelectionCandidate(symbol: string) {
  activeSelectionSymbol.value = symbol
}

function openCompanyFromWatchlist(symbol: string) {
  navigateTo('companies')
  void focusCompany(symbol)
}

function handleCompanyPageSizeChange() {
  companyPager.page = 1
  if (selectedSymbol.value) {
    const index = sortedCompanies.value.findIndex((company) => company.symbol === selectedSymbol.value)
    if (index >= 0) {
      companyPager.page = Math.floor(index / companyPager.pageSize) + 1
    }
  }
}

function syncSelectedCompany() {
  if (sortedCompanies.value.length === 0) {
    selectedSymbol.value = ''
    activeResearch.value = null
    activeConsensus.value = null
    return
  }
  const exists = sortedCompanies.value.some((company) => company.symbol === selectedSymbol.value)
  if (!exists) {
    selectedSymbol.value = sortedCompanies.value[0].symbol
    companyPager.page = 1
  }
}

function formatNumber(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return Number(value).toFixed(2)
}

function formatScore(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return Number(value).toFixed(1)
}

function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${Number(value).toFixed(2)}%`
}

function formatRatioPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${(Number(value) * 100).toFixed(2)}%`
}

function changeClass(value: number | null | undefined) {
  if (value === null || value === undefined) return ''
  if (value > 0) return 'price-up'
  if (value < 0) return 'price-down'
  return ''
}

async function loadAll() {
  loading.value = true
  let partialFailure = false
  const settle = <T>(request: Promise<T>, onSuccess: (value: T) => void) =>
    request
      .then(async (value) => {
        onSuccess(value)
        await nextTick()
      })
      .catch(() => {
        partialFailure = true
      })

  try {
    await Promise.allSettled([
      settle(fetchPolicyThemes(), (value) => {
        themes.value = value
        seedTrendForm(themes.value, companies.value)
      }),
      settle(fetchCompanies(), (value) => {
        companies.value = value
        seedTrendForm(themes.value, companies.value)
      }),
      settle(fetchRules(), (value) => {
        rules.value = value
      }),
      settle(fetchWatchlist(), (value) => {
        watchlist.value = value
      })
    ])

    syncSelectedCompany()
    await nextTick()
    renderThemeChart()
    if (rules.value.length > 0 && activeCompany.value) {
      try {
        await Promise.allSettled([
          runEvaluation(),
          loadActiveResearch(activeCompany.value.symbol),
          loadActiveConsensus(activeCompany.value.symbol)
        ])
      } catch (error) {
        partialFailure = true
      }
    }
    await loadLatestTrendAnalysis()
    await loadTrendHistory()
    if (partialFailure) {
      ElMessage.warning('部分外部数据源加载较慢，已先展示可用数据')
    }
  } catch (error) {
    ElMessage.error('数据加载失败，请确认后端服务已启动')
  } finally {
    loading.value = false
  }
}

async function refreshLlmConfig() {
  configLoading.value = true
  try {
    llmConfig.value = await fetchLlmConfig()
  } catch (error) {
    aiError.value = extractErrorMessage(error)
  } finally {
    configLoading.value = false
  }
}

async function loadRuntimeConfig() {
  runtimeConfigLoading.value = true
  try {
    const data = await fetchRuntimeConfig()
    runtimeConfig.value = data
    runtimeConfigForm.value = cloneRuntimeConfig(data)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    runtimeConfigLoading.value = false
  }
}

async function loadLlmRuntimeConfig() {
  llmRuntimeConfigLoading.value = true
  try {
    const [llm, snapshot] = await Promise.all([
      fetchLlmRuntimeConfig(),
      fetchRuntimeConfig()
    ])
    runtimeConfigForm.value.llm = { ...llm, apiKey: '' }
    runtimeConfigForm.value.llmRevision = snapshot.llmRevision
    runtimeConfigForm.value.updatedAt = snapshot.updatedAt
    runtimeConfig.value = snapshot
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    llmRuntimeConfigLoading.value = false
  }
}

async function saveLlmRuntimeConfig() {
  llmRuntimeConfigSaving.value = true
  try {
    const nextApiKey = runtimeConfigForm.value.llm.apiKey?.trim()
    const updated = await updateLlmRuntimeConfig({
      ...runtimeConfigForm.value.llm,
      apiKey: nextApiKey ? nextApiKey : null
    })
    runtimeConfigForm.value.llm = { ...updated, apiKey: '' }
    const snapshot = await fetchRuntimeConfig()
    runtimeConfigForm.value.llmRevision = snapshot.llmRevision
    runtimeConfigForm.value.updatedAt = snapshot.updatedAt
    runtimeConfig.value = snapshot
    ElMessage.success('大模型配置已保存并生效')
    await refreshLlmConfig()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    llmRuntimeConfigSaving.value = false
  }
}

async function loadPolicySources() {
  policySourcesLoading.value = true
  try {
    const [sources, snapshot] = await Promise.all([
      fetchPolicySources(),
      fetchRuntimeConfig()
    ])
    runtimeConfigForm.value.policySources = sources.map((source) => ({ ...source }))
    runtimeConfigForm.value.policySourcesRevision = snapshot.policySourcesRevision
    runtimeConfigForm.value.updatedAt = snapshot.updatedAt
    runtimeConfig.value = snapshot
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    policySourcesLoading.value = false
  }
}

async function savePolicySources() {
  policySourcesSaving.value = true
  try {
    const updated = await updatePolicySources(
      runtimeConfigForm.value.policySources.map((source) => ({ ...source }))
    )
    runtimeConfigForm.value.policySources = updated.map((source) => ({ ...source }))
    const snapshot = await fetchRuntimeConfig()
    runtimeConfigForm.value.policySourcesRevision = snapshot.policySourcesRevision
    runtimeConfigForm.value.updatedAt = snapshot.updatedAt
    runtimeConfig.value = snapshot
    ElMessage.success('政策源配置已保存并生效')
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    policySourcesSaving.value = false
  }
}

function changeLlmProvider(provider: string) {
  const defaults = LLM_PROVIDER_DEFAULTS[provider]
  if (!defaults) return
  runtimeConfigForm.value.llm = {
    ...runtimeConfigForm.value.llm,
    provider,
    ...defaults,
    apiKey: '',
    apiKeyConfigured: false,
    apiKeySource: 'missing'
  }
}

function addPolicySource() {
  runtimeConfigForm.value.policySources.push({
    name: '新政策源',
    type: 'html',
    url: 'https://',
    weight: 80
  })
}

function removePolicySource(index: number) {
  runtimeConfigForm.value.policySources.splice(index, 1)
}

async function previewTrend() {
  previewLoading.value = true
  aiError.value = ''
  try {
    trendPromptPreview.value = await previewTrendPrompt(trendForm)
    saveTrendDailyCache()
    ElMessage.success('提示词预览已生成')
  } catch (error) {
    aiError.value = extractErrorMessage(error)
  } finally {
    previewLoading.value = false
  }
}

async function loadLatestTrendAnalysis() {
  try {
    const latest = await fetchLatestTrendAnalysis(trendForm)
    if (latest) {
      trendAnalysis.value = latest
      trendPromptPreview.value = null
      saveTrendDailyCache()
    }
  } catch (_error) {
    // Keep the current state when archive lookup is unavailable.
  }
}

async function loadTrendHistory() {
  try {
    trendHistory.value = await fetchTrendAnalysisHistory(10)
  } catch (_error) {
    // History is supplemental and should not block the main workflow.
  }
}

async function runTrendAnalysis() {
  if (aiLoading.value) return
  aiLoading.value = true
  aiError.value = ''
  try {
    trendAnalysis.value = await analyzeTrend(trendForm)
    trendPromptPreview.value = null
    saveTrendDailyCache()
    await loadTrendHistory()
    ElMessage.success('AI 趋势分析完成')
  } catch (error) {
    aiError.value = extractErrorMessage(error)
  } finally {
    aiLoading.value = false
  }
}

async function runEvaluation() {
  if (!activeCompany.value) return
  evaluations.value = await evaluateRules({
    symbol: activeCompany.value.symbol,
    factors: activeCompany.value.factors
  })
}

function seedTrendForm(themeData: PolicyTheme[], companyData: CompanyProfile[]) {
  if (trendFormSeeded.value) return
  const focusThemes = Array.from(new Set(themeData.flatMap((theme) => [theme.name, ...theme.chainSegments]))).slice(0, 6)
  const knownCompanies = companyData.slice(0, 6).map((company) => company.name)
  const policySignals = themeData
    .flatMap((theme) => theme.signals.map((signal) => `${theme.name}：${signal.summary}`))
    .slice(0, 4)
  trendForm.focusThemes = focusThemes.length > 0 ? focusThemes : trendForm.focusThemes
  trendForm.knownCompanies = knownCompanies.length > 0 ? knownCompanies : trendForm.knownCompanies
  if (policySignals.length > 0) {
    trendForm.contentExcerpt = policySignals.join('\n')
  }
  trendFormSeeded.value = true
}

function asRecord(value: JsonValue | undefined): Record<string, JsonValue> | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value
  }
  return null
}

function asArray(value: JsonValue | undefined): JsonValue[] {
  return Array.isArray(value) ? value : []
}

function textValue(value: JsonValue | undefined, fallback: string) {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return fallback
}

function stringList(value: JsonValue | undefined) {
  return asArray(value)
    .map((item) => textValue(item, ''))
    .filter(Boolean)
}

function extractErrorMessage(error: unknown) {
  const maybeError = error as {
    response?: { data?: ApiErrorBody | { message?: string }; status?: number }
    message?: string
  }
  const data = maybeError.response?.data
  if (data && typeof data === 'object' && 'message' in data && typeof data.message === 'string') {
    return data.message
  }
  return maybeError.message ?? '请求失败'
}

function renderThemeChart() {
  if (!themeChartRef.value) return
  if (!themeChart) {
    themeChart = init(themeChartRef.value)
  }
  themeChart.setOption({
    grid: { left: 8, right: 8, top: 20, bottom: 12, containLabel: true },
    xAxis: {
      type: 'value',
      max: 100,
      axisLine: { show: false },
      splitLine: { lineStyle: { color: '#d8e1dc' } }
    },
    yAxis: {
      type: 'category',
      data: sortedThemes.value.map((theme) => theme.name),
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        type: 'bar',
        data: sortedThemes.value.map((theme) => policyRecommendation(theme)),
        barWidth: 18,
        itemStyle: {
          borderRadius: 999,
          color: '#1f6b57'
        }
      }
    ],
    tooltip: { trigger: 'axis' }
  })
  themeChart.resize()
}

function handleHashChange() {
  syncRouteHash()
}

function handleResize() {
  themeChart?.resize()
}

watch(currentPage, async (page) => {
  if (page === 'settings') {
    void loadRuntimeConfig()
  }
  await nextTick()
  renderThemeChart()
})

watch([sortedCompanies, sortedWatchlist], () => {
  syncSelectedCompany()
})

watch(
  trendForm,
  () => {
    saveTrendDailyCache()
  },
  { deep: true }
)

onMounted(() => {
  if (!window.location.hash) {
    window.location.hash = '#/overview'
  }
  window.addEventListener('hashchange', handleHashChange)
  window.addEventListener('resize', handleResize)
  syncRouteHash()
  restoreTrendDailyCache()
  void loadAll()
  void refreshLlmConfig()
  void loadRuntimeConfig()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', handleHashChange)
  window.removeEventListener('resize', handleResize)
  themeChart?.dispose()
  themeChart = null
})
</script>
