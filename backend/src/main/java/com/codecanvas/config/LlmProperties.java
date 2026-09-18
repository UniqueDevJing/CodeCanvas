package com.codecanvas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型层配置。
 *
 * <p>设计要点：模型厂商通过配置切换，业务代码只依赖 {@code LlmProvider} 接口。
 * 本地没有可用密钥时 {@code mode=auto} 会自动退回离线模拟引擎，保证项目随时可跑通。
 */
@ConfigurationProperties(prefix = "codecanvas.llm")
public class LlmProperties {

    /** auto=有密钥用真实模型，否则用离线引擎；real=强制真实；mock=强制离线。 */
    private String mode = "auto";

    /** 当前启用的厂商协议：openai | anthropic。 */
    private String activeProvider = "openai";

    /**
     * 首字节超时（秒）。流式调用若这么久还没有任何内容返回，直接判定该候选不可用并降级。
     *
     * <p>为什么单独设这个：整体超时是为了兜住"生成内容很长"，但它会让一个卡死的模型把用户
     * 拖满整个超时时间。有了首字节超时，坏候选几秒内就被跳过，用户等待时间大幅下降。
     */
    private int firstTokenSeconds = 45;

    private Endpoint openai = new Endpoint();
    private Endpoint anthropic = new Endpoint();
    private Models models = new Models();
    private Pricing pricing = new Pricing();

    public static class Endpoint {
        /** OpenAI 兼容协议默认指向百炼（DashScope），可换成 DeepSeek / GLM / 硅基流动 等任意兼容端点。 */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        /** 整体请求超时（秒）。代码生成输出很长，超时给足。 */
        private int timeoutSeconds = 180;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** 分级路由的目标模型：同一个请求按复杂度落到不同档位。 */
    public static class Models {
        private String flagship = "deepseek-reasoner";
        private String standard = "deepseek-chat";
        private String light = "deepseek-chat";

        public String getFlagship() {
            return flagship;
        }

        public void setFlagship(String flagship) {
            this.flagship = flagship;
        }

        public String getStandard() {
            return standard;
        }

        public void setStandard(String standard) {
            this.standard = standard;
        }

        public String getLight() {
            return light;
        }

        public void setLight(String light) {
            this.light = light;
        }
    }

    /** 单价（元 / 千 token），仅用于成本看板估算，按实际厂商价格调整。 */
    public static class Pricing {
        private double flagshipInput = 0.004;
        private double flagshipOutput = 0.016;
        private double standardInput = 0.001;
        private double standardOutput = 0.002;
        private double lightInput = 0.0005;
        private double lightOutput = 0.001;

        public double getFlagshipInput() {
            return flagshipInput;
        }

        public void setFlagshipInput(double v) {
            this.flagshipInput = v;
        }

        public double getFlagshipOutput() {
            return flagshipOutput;
        }

        public void setFlagshipOutput(double v) {
            this.flagshipOutput = v;
        }

        public double getStandardInput() {
            return standardInput;
        }

        public void setStandardInput(double v) {
            this.standardInput = v;
        }

        public double getStandardOutput() {
            return standardOutput;
        }

        public void setStandardOutput(double v) {
            this.standardOutput = v;
        }

        public double getLightInput() {
            return lightInput;
        }

        public void setLightInput(double v) {
            this.lightInput = v;
        }

        public double getLightOutput() {
            return lightOutput;
        }

        public void setLightOutput(double v) {
            this.lightOutput = v;
        }
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(String activeProvider) {
        this.activeProvider = activeProvider;
    }

    public int getFirstTokenSeconds() {
        return firstTokenSeconds;
    }

    public void setFirstTokenSeconds(int firstTokenSeconds) {
        this.firstTokenSeconds = firstTokenSeconds;
    }

    public Endpoint getOpenai() {
        return openai;
    }

    public void setOpenai(Endpoint openai) {
        this.openai = openai;
    }

    public Endpoint getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(Endpoint anthropic) {
        this.anthropic = anthropic;
    }

    public Models getModels() {
        return models;
    }

    public void setModels(Models models) {
        this.models = models;
    }

    public Pricing getPricing() {
        return pricing;
    }

    public void setPricing(Pricing pricing) {
        this.pricing = pricing;
    }
}
