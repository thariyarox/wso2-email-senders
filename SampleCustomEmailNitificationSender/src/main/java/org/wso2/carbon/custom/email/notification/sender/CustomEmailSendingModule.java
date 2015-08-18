package org.wso2.carbon.custom.email.notification.sender;

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
import org.wso2.carbon.custom.email.notification.sender.internal.CustomEmailNotificationSenderDSComponent;
import org.wso2.carbon.identity.mgt.mail.AbstractEmailSendingModule;
import org.wso2.carbon.identity.mgt.mail.Notification;
import org.wso2.carbon.identity.mgt.util.Utils;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.claim.ClaimMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomEmailSendingModule extends AbstractEmailSendingModule {

    private static Log log = LogFactory.getLog(CustomEmailSendingModule.class);
    private Notification notification;

    /**
     * Retrieve the Claim URIs of Default WSO2 Claim Dialect
     *
     * @return WSO2 Claim Dialect URIs
     */
    private List<String> getDefaultCarbonClaimUris() {
        List<String> carbonClaimUris = new ArrayList<String>();
        try {
            ClaimManager claimManager =
                    (ClaimManager) CustomEmailNotificationSenderDSComponent.getRealmService().getTenantUserRealm(
                            PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId()).getClaimManager();

            ClaimMapping[] claimMappings = (ClaimMapping[]) claimManager.getAllClaimMappings(
                    UserCoreConstants.DEFAULT_CARBON_DIALECT);

            if (claimMappings != null && claimMappings.length > 0) {
                for (ClaimMapping claimMapping : claimMappings) {
                    carbonClaimUris.add(claimMapping.getClaim().getClaimUri());
                }
            }
        } catch (UserStoreException e) {
            // The claim URIs of default carbon dialect cannot be obtained
            log.error("Error while obtaining claim mappings of default carbon dialect", e);
        }

        return carbonClaimUris;
    }

    /**
     * Send email notification to the user
     */
    public void sendEmail() {

        Map<String, String> headerMap = new HashMap<String, String>();

        try {
            if (this.notification == null) {
                throw new NullPointerException(
                        "Notification not set. Please set the notification before sending messages");
            }

            String body = notification.getBody();

            // Extract username and userstore domain of the user from the body
            String userId = StringUtils.substringBetween(body, "[[username-", "]]");
            String userStoreDomain = StringUtils.substringBetween(body, "[[userstore-domain-", "]]");

            // Remove the username and userstore domain of the user from the body
            body = StringUtils.remove(body, "[[username-" + userId + "]]");
            body = StringUtils.remove(body, "[[userstore-domain-" + userStoreDomain + "]]");

            if (log.isDebugEnabled()) {
                log.debug("Username : " + userId + " and userstore domain : " + userStoreDomain +
                          " extracted from email body");
            }

            PrivilegedCarbonContext.startTenantFlow();

            String tenantDomain = notificationData.getDomainName();
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantDomain(tenantDomain);
            int tenantId = carbonContext.getTenantId(true);

            Map<String, String> claimsMap = new HashMap<String, String>();

            if (StringUtils.isNotBlank(userId) && StringUtils.isNotBlank(userStoreDomain)) {

                // Retrieve the Default Carbon Claim Uris for the tenant
                List<String> carbonClaimUris = getDefaultCarbonClaimUris();

                // Retrieve claim values for the user
                for (String claimUri : carbonClaimUris) {
                    String claimValue =
                            Utils.getClaimFromUserStoreManager(userStoreDomain + "/" + userId, tenantId, claimUri);

                    if (StringUtils.isNotBlank(claimValue)) {
                        claimsMap.put(claimUri, claimValue);
                    }
                }
            }

            // Replace the claim uri placeholders with values from the notification body
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

            if (log.isDebugEnabled()) {
                log.debug("Sending " + "user mail to " + this.notification.getSendTo());
            }
            serviceClient.fireAndForget(payload);

            if (log.isDebugEnabled()) {
                log.debug("Email content : " + this.notification.getBody());
            }

            log.info("Email has been sent to " + this.notification.getSendTo());
        } catch (Exception e) {
            log.error("Failed Sending Email ", e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }

    }

    /**
     * Replace the {user-parameters} in the text with the values
     *
     * @param text the initial text
     * @param userParameters mapping of the key and its value
     * @return the final text to be sent in the email
     */
    public static String replacePlaceHolders(String text, Map<String, String> userParameters) {
        if (userParameters != null && userParameters.size() > 0) {
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
