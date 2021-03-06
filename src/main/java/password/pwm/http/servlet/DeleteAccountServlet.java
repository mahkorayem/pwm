/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet;

import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.bean.UserIdentity;
import password.pwm.config.ActionConfiguration;
import password.pwm.config.PwmSetting;
import password.pwm.config.profile.ProfileType;
import password.pwm.config.profile.DeleteAccountProfile;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.HttpMethod;
import password.pwm.http.PwmRequest;
import password.pwm.http.bean.DeleteAccountBean;
import password.pwm.svc.event.AuditEvent;
import password.pwm.svc.event.AuditRecord;
import password.pwm.svc.event.AuditRecordFactory;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;
import password.pwm.util.operations.ActionExecutor;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@WebServlet(
        name="SelfDeleteServlet",
        urlPatterns={
                PwmConstants.URL_PREFIX_PRIVATE + "/delete",
                PwmConstants.URL_PREFIX_PRIVATE + "/DeleteAccount"
        }
)
public class DeleteAccountServlet extends AbstractPwmServlet {

    private static final PwmLogger LOGGER = PwmLogger.forClass(DeleteAccountServlet.class);

    public enum DeleteAccountAction implements AbstractPwmServlet.ProcessAction {
        agree(HttpMethod.POST),
        delete(HttpMethod.POST),
        reset(HttpMethod.POST),

        ;

        private final HttpMethod method;

        DeleteAccountAction(HttpMethod method)
        {
            this.method = method;
        }

        public Collection<HttpMethod> permittedMethods()
        {
            return Collections.singletonList(method);
        }
    }

    protected DeleteAccountAction readProcessAction(final PwmRequest request)
            throws PwmUnrecoverableException

    {
        try {
            return DeleteAccountAction.valueOf(request.readParameterAsString(PwmConstants.PARAM_ACTION_REQUEST));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected void processAction(PwmRequest pwmRequest)
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final DeleteAccountProfile deleteAccountProfile = pwmRequest.getPwmSession().getSessionManager().getSelfDeleteProfile(pwmApplication);
        final DeleteAccountBean deleteAccountBean = pwmApplication.getSessionStateService().getBean(pwmRequest, DeleteAccountBean.class);

        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.DELETE_ACCOUNT_ENABLE)) {
            pwmRequest.respondWithError(new ErrorInformation(PwmError.ERROR_SERVICE_NOT_AVAILABLE, "Setting " + PwmSetting.DELETE_ACCOUNT_ENABLE.toMenuLocationDebug(null,null) + " is not enabled."));
            return;
        }

        if (deleteAccountProfile == null) {
            pwmRequest.respondWithError(new ErrorInformation(PwmError.ERROR_NO_PROFILE_ASSIGNED));
            return;
        }

        final DeleteAccountAction action = readProcessAction(pwmRequest);
        if (action != null) {
            switch (action) {
                case agree:
                    handleAgreeRequest(pwmRequest, deleteAccountBean);
                    break;

                case reset:
                    handleResetRequest(pwmRequest);
                    return;

                case delete:
                    handleDeleteRequest(pwmRequest, deleteAccountProfile);
                    return;

            }
        }

        advancedToNextStage(pwmRequest, deleteAccountProfile, deleteAccountBean);
    }

    private void advancedToNextStage(PwmRequest pwmRequest, final DeleteAccountProfile profile, final DeleteAccountBean bean)
            throws PwmUnrecoverableException, ServletException, IOException
    {

        final String selfDeleteAgreementText = profile.readSettingAsLocalizedString(
                PwmSetting.DELETE_ACCOUNT_AGREEMENT,
                pwmRequest.getPwmSession().getSessionStateBean().getLocale()
        );

        if (selfDeleteAgreementText != null && !selfDeleteAgreementText.trim().isEmpty()) {
            if (!bean.isAgreementPassed()) {
                final MacroMachine macroMachine = pwmRequest.getPwmSession().getSessionManager().getMacroMachine(pwmRequest.getPwmApplication());
                final String expandedText = macroMachine.expandMacros(selfDeleteAgreementText);
                pwmRequest.setAttribute(PwmRequest.Attribute.AgreementText, expandedText);
                pwmRequest.forwardToJsp(PwmConstants.JSP_URL.SELF_DELETE_AGREE);
                return;
            }
        }

        pwmRequest.forwardToJsp(PwmConstants.JSP_URL.SELF_DELETE_CONFIRM);
    }

    private void handleResetRequest(
            final PwmRequest pwmRequest
    )
            throws ServletException, IOException, PwmUnrecoverableException, ChaiUnavailableException
    {
        pwmRequest.getPwmApplication().getSessionStateService().clearBean(pwmRequest, DeleteAccountBean.class);
        pwmRequest.sendRedirectToContinue();
    }

    private void handleAgreeRequest(
            final PwmRequest pwmRequest,
            final DeleteAccountBean deleteAccountBean
    )
            throws ServletException, IOException, PwmUnrecoverableException, ChaiUnavailableException
    {
        LOGGER.debug(pwmRequest, "user accepted agreement");

        if (!deleteAccountBean.isAgreementPassed()) {
            deleteAccountBean.setAgreementPassed(true);
            AuditRecord auditRecord = new AuditRecordFactory(pwmRequest).createUserAuditRecord(
                    AuditEvent.AGREEMENT_PASSED,
                    pwmRequest.getUserInfoIfLoggedIn(),
                    pwmRequest.getSessionLabel(),
                    ProfileType.DeleteAccount.toString()
            );
            pwmRequest.getPwmApplication().getAuditManager().submit(auditRecord);
        }
    }

    private void handleDeleteRequest(
            final PwmRequest pwmRequest,
            final DeleteAccountProfile profile
    )
            throws ServletException, IOException, PwmUnrecoverableException, ChaiUnavailableException {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final DeleteAccountProfile deleteAccountProfile = pwmRequest.getPwmSession().getSessionManager().getSelfDeleteProfile(pwmApplication);
        final UserIdentity userIdentity = pwmRequest.getUserInfoIfLoggedIn();


        {  // execute configured actions
            final List<ActionConfiguration> actions = deleteAccountProfile.readSettingAsAction(PwmSetting.DELETE_ACCOUNT_ACTIONS);
            if (actions != null && !actions.isEmpty()) {
                LOGGER.debug(pwmRequest, "executing configured actions to user " + userIdentity);


                final ActionExecutor actionExecutor = new ActionExecutor.ActionExecutorSettings(pwmApplication, userIdentity)
                        .setExpandPwmMacros(true)
                        .setMacroMachine(pwmRequest.getPwmSession().getSessionManager().getMacroMachine(pwmApplication))
                        .createActionExecutor();

                try {
                    actionExecutor.executeActions(actions, pwmRequest.getPwmSession());
                } catch (PwmOperationalException e) {
                    LOGGER.error("error during user delete action execution: ", e);
                    throw new PwmUnrecoverableException(e.getErrorInformation(),e.getCause());
                }
            }
        }

        // mark the event log
        pwmApplication.getAuditManager().submit(AuditEvent.DELETE_ACCOUNT, pwmRequest.getPwmSession().getUserInfoBean(), pwmRequest.getPwmSession());

        // perform ldap entry delete.
        if (deleteAccountProfile.readSettingAsBoolean(PwmSetting.DELETE_ACCOUNT_DELETE_USER_ENTRY)) {
            final ChaiUser chaiUser = pwmApplication.getProxiedChaiUser(pwmRequest.getUserInfoIfLoggedIn());
            try {
                chaiUser.getChaiProvider().deleteEntry(chaiUser.getEntryDN());
            } catch (ChaiException e) {
                final PwmUnrecoverableException pwmException = PwmUnrecoverableException.fromChaiException(e);
                LOGGER.error("error during user delete", pwmException);
                throw pwmException;
            }
        }

        // clear the delete bean
        pwmApplication.getSessionStateService().clearBean(pwmRequest, DeleteAccountBean.class);

        final String nextUrl = profile.readSettingAsString(PwmSetting.DELETE_ACCOUNT_NEXT_URL);
        if (nextUrl != null && !nextUrl.isEmpty()) {
            final MacroMachine macroMachine = pwmRequest.getPwmSession().getSessionManager().getMacroMachine(pwmApplication);
            final String macroedUrl = macroMachine.expandMacros(nextUrl);
            LOGGER.debug(pwmRequest, "settinging forward url to post-delete next url: " + macroedUrl);
            pwmRequest.getPwmSession().getSessionStateBean().setForwardURL(macroedUrl);
        }

        // delete finished, so logout and redirect.
        pwmRequest.getPwmSession().unauthenticateUser(pwmRequest);
        pwmRequest.sendRedirectToContinue();
    }



}