package interview.guide.modules.resumedocument.model;

/**
 * 在线简历文档的输入与内容上限，供注解与 Service 共用。
 */
public final class ResumeDocumentLimits {

    private ResumeDocumentLimits() {}

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_TEMPLATE_CODE_LENGTH = 64;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_HISTORY_TURNS = 8;
    public static final int MAX_OPERATIONS = 20;
    public static final int MAX_LIST_ITEMS = 30;
    public static final int MAX_HIGHLIGHTS_PER_PROJECT = 50;
    public static final int MAX_HIGHLIGHTS_PER_INTERNSHIP = 50;
    public static final int MAX_SKILLS = 100;
    public static final int MAX_SHORT_TEXT = 200;
    public static final int MAX_LONG_TEXT = 4000;
    public static final int MAX_SOURCE_EXCERPT = 6000;
    public static final String DEFAULT_TEMPLATE_CODE = "default";
}
