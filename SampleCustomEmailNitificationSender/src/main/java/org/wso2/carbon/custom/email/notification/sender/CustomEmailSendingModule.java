package org.wso2.carbon.custom.email.notification.sender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.mail.MailConstants;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.CarbonConfigurationContextFactory;
import org.wso2.carbon.identity.mgt.internal.IdentityMgtServiceComponent;
import org.wso2.carbon.identity.mgt.mail.EmailConfig;
import org.wso2.carbon.identity.mgt.mail.Notification;
import org.wso2.carbon.identity.mgt.util.Utils;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.claim.ClaimMapping;
import org.wso2.carbon.identity.mgt.mail.AbstractEmailSendingModule;

public class CustomEmailSendingModule extends AbstractEmailSendingModule {

    public static final String CONF_STRING = "confirmation";
    private static Log log = LogFactory.getLog(CustomEmailSendingModule.class);
    private Notification notification;
    private static List<String> carbonClaimUris = new ArrayList<String>();

    //private static void initializeDefaultClaims(){
    static{
        try {
            ClaimManager claimManager = (ClaimManager) IdentityMgtServiceComponent.getRealmService().getTenantUserRealm(
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId()).getClaimManager();

            ClaimMapping[] claimMappings = (ClaimMapping[]) claimManager.getAllClaimMappings(
                    UserCoreConstants.DEFAULT_CARBON_DIALECT);

            if(claimMappings != null && claimMappings.length > 0) {
                for(ClaimMapping claimMapping : claimMappings){
                    carbonClaimUris.add(claimMapping.getClaim().getClaimUri());
                }
            }
        } catch (UserStoreException e) {
            // Ignore this if the claim URIs of default carbon dialect cannot be obtained
            log.error("Error while obtaining claim mappings of default carbon dialect", e);
        }
    }

    public void sendEmail(){

        Map<String, String> headerMap = new HashMap<String, String>();

        try {
            if(this.notification == null) {
                throw new NullPointerException("Notification not set. Please set the notification before sending messages");
            }

            String body = notification.getBody();
            String userId = StringUtils.substringBetween(body, "[[username-", "]]");
            String userStoreDomain = StringUtils.substringBetween(body, "[[userstore-domain-", "]]");
            //String userTenantDomain = StringUtils.substringBetween(body, "[[tenant-domain-", "]]");

            body = StringUtils.remove(body, "[[username-" + userId + "]]");
            body = StringUtils.remove(body, "[[userstore-domain-" + userStoreDomain + "]]");
            //body = StringUtils.remove(body, "[[tenant-domain-" + userTenantDomain + "]]");


            PrivilegedCarbonContext.startTenantFlow();

            String tenantDomain = notificationData.getDomainName();
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantDomain(tenantDomain);
            int tenantId = carbonContext.getTenantId(true);

            Map<String, String> claimsMap = new HashMap<String, String>();

            //get user claims
            for(String claimUri : carbonClaimUris) {
                String claimValue = Utils.getClaimFromUserStoreManager(userStoreDomain + "/" + userId, tenantId, claimUri);

                if(claimValue != null && claimValue.trim().length() > 0) {
                    claimsMap.put(claimUri, claimValue);
                }
            }

            //Replace the claim uris from the notification
            body = replacePlaceHolders(body, claimsMap);
            notification.setBody(body);


            headerMap.put(MailConstants.MAIL_HEADER_SUBJECT, this.notification.getSubject());

            OMElement payload = OMAbstractFactory.getOMFactory().createOMElement(
                    BaseConstants.DEFAULT_TEXT_WRAPPER, null);
            StringBuilder contents = new StringBuilder();
            contents.append(this.notification.getBody())
                    .append(System.getProperty("line.separator"))
                    .append(System.getProperty("line.separator"))
                    .append(this.notification.getFooter());
            payload.setText(contents.toString());
            ServiceClient serviceClient;
            ConfigurationContext configContext = CarbonConfigurationContextFactory
                    .getConfigurationContext();
            if (configContext != null) {
                serviceClient = new ServiceClient(configContext, null);
            } else {
                serviceClient = new ServiceClient();
            }
            Options options = new Options();
            options.setProperty(Constants.Configuration.ENABLE_REST, Constants.VALUE_TRUE);
            options.setProperty(MessageContext.TRANSPORT_HEADERS, headerMap);
            options.setProperty(MailConstants.TRANSPORT_MAIL_FORMAT,
                                MailConstants.TRANSPORT_FORMAT_TEXT);
            options.setTo(new EndpointReference("mailto:" + this.notification.getSendTo()));
            serviceClient.setOptions(options);
            log.debug("Sending " + "user credentials configuration mail to " + this.notification.getSendTo());
            serviceClient.fireAndForget(payload);

            log.debug("Email content : " + this.notification.getBody());
            log.info("User credentials configuration mail has been sent to " + this.notification.getSendTo());
        } catch (Exception e) {
            log.error("Failed Sending Email ", e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }

    }

    /**
     *
     * @param emailConfig
     * @return
     */
    //	private String getEmailMessage(Map<String, String> userParameters) {
    //		StringBuffer message = new StringBuffer();
    //		for (Map.Entry<String, String> entry : userParameters.entrySet()) {
    //			message.append("\n" + entry.getKey() + " : " + entry.getValue());
    //		}
    //		return message.toString();
    //	}

    public String getRequestMessage(EmailConfig emailConfig) {

        String msg;
        String targetEpr = emailConfig.getTargetEpr();
        if (emailConfig.getEmailBody().length() == 0) {
            msg = EmailConfig.DEFAULT_VALUE_MESSAGE + "\n";
            if (notificationData.getNotificationCode() != null) {
                msg =
                        msg + targetEpr + "?" + CONF_STRING + "=" + notificationData.getNotificationCode() +
                        "\n";
            }
        } else {
            msg = emailConfig.getEmailBody() + "\n";
            if (notificationData.getNotificationCode() != null) {
                //				msg =
                //				      msg + targetEpr + "?" + CONF_STRING + "=" + notificationData.getNotificationCode() +
                //				              "\n";
            }
        }
        if (emailConfig.getEmailFooter() != null) {
            msg = msg + "\n" + emailConfig.getEmailFooter();
        }
        return msg;
    }

    /**
     * Replace the {user-parameters} in the config file with the respective
     * values
     *
     * @param text
     *            the initial text
     * @param userParameters
     *            mapping of the key and its value
     * @return the final text to be sent in the email
     */
    public static String replacePlaceHolders(String text, Map<String, String> userParameters) {
        if (userParameters != null) {
            for (Map.Entry<String, String> entry : userParameters.entrySet()) {
                String key = entry.getKey();
                if (key != null && entry.getValue() != null) {
                    text = text.replaceAll("\\{" + key + "\\}", entry.getValue());
                }
            }
        }
        return text;
    }

    @Override
    public void setNotification(Notification notification) {
        this.notification = notification;
    }

    @Override
    public Notification getNotification() {
        return this.notification;
    }
}
