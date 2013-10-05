/*
 * Copyright 2004 - 2013 Wayne Grant
 *           2013 Kai Kramer
 *
 * This file is part of KeyStore Explorer.
 *
 * KeyStore Explorer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeyStore Explorer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeyStore Explorer.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.keystore_explorer.gui.actions;

import static java.awt.Dialog.ModalityType.APPLICATION_MODAL;

import java.awt.Toolkit;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;

import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import net.sf.keystore_explorer.crypto.Password;
import net.sf.keystore_explorer.crypto.x509.X509CertUtil;
import net.sf.keystore_explorer.gui.CurrentDirectory;
import net.sf.keystore_explorer.gui.FileChooserFactory;
import net.sf.keystore_explorer.gui.KseFrame;
import net.sf.keystore_explorer.gui.dialogs.DViewCertificate;
import net.sf.keystore_explorer.gui.error.DError;
import net.sf.keystore_explorer.gui.error.DProblem;
import net.sf.keystore_explorer.gui.error.Problem;
import net.sf.keystore_explorer.utilities.history.HistoryAction;
import net.sf.keystore_explorer.utilities.history.KeyStoreHistory;
import net.sf.keystore_explorer.utilities.history.KeyStoreState;

/**
 * Action to import a CA Reply into the selected key pair entry.
 * 
 */
public class ImportCaReplyAction extends AuthorityCertificatesAction implements HistoryAction {
	/**
	 * Construct action.
	 * 
	 * @param kseFrame
	 *            KeyStore Explorer frame
	 */
	public ImportCaReplyAction(KseFrame kseFrame) {
		super(kseFrame);

		putValue(LONG_DESCRIPTION, res.getString("ImportCaReplyAction.statusbar"));
		putValue(NAME, res.getString("ImportCaReplyAction.text"));
		putValue(SHORT_DESCRIPTION, res.getString("ImportCaReplyAction.tooltip"));
		putValue(
				SMALL_ICON,
				new ImageIcon(Toolkit.getDefaultToolkit().createImage(
						getClass().getResource(res.getString("ImportCaReplyAction.image")))));
	}

	public String getHistoryDescription() {
		return (String) getValue(NAME);
	}

	/**
	 * Do action.
	 */
	protected void doAction() {
		try {
			KeyStoreHistory history = kseFrame.getActiveKeyStoreHistory();
			KeyStoreState currentState = history.getCurrentState();

			String alias = kseFrame.getSelectedEntryAlias();

			Password password = getEntryPassword(alias, currentState);

			if (password == null) {
				return;
			}

			KeyStoreState newState = currentState.createBasisForNextState(this);

			KeyStore keyStore = newState.getKeyStore();

			Key privateKey = keyStore.getKey(alias, password.toCharArray());

			File caReplyFile = chooseCaFile();
			if (caReplyFile == null) {
				return;
			}

			X509Certificate[] certs = openCaReply(caReplyFile);

			if ((certs == null) || (certs.length == 0)) {
				return;
			}

			certs = X509CertUtil.orderX509CertChain(certs);

			X509Certificate[] exitingEntryCerts = X509CertUtil.orderX509CertChain(X509CertUtil
					.convertCertificates(keyStore.getCertificateChain(alias)));

			if (!exitingEntryCerts[0].getPublicKey().equals(certs[0].getPublicKey())) {
				JOptionPane.showMessageDialog(frame, res.getString("ImportCaReplyAction.NoMatchPubKeyCaReply.message"),
						res.getString("ImportCaReplyAction.ImportCaReply.Title"), JOptionPane.WARNING_MESSAGE);
				return;
			}

			// Holds the new certificate chain for the entry should the import
			// succeed
			X509Certificate[] newCertChain = null;

			if (!applicationSettings.getEnableImportCaReplyTrustCheck()) {
				newCertChain = certs;
			} else {
				KeyStore caCertificates = getCaCertificates();
				KeyStore windowsTrustedRootCertificates = getWindowsTrustedRootCertificates();

				/*
				 * PKCS #7 reply - try and match the self-signed root with any
				 * of the certificates in the CA Certificates or current
				 * KeyStore
				 */
				if (certs.length > 1) {
					X509Certificate rootCert = certs[certs.length - 1];
					String matchAlias = null;

					if (caCertificates != null) // Match against CA Certificates
												// KeyStore
					{
						matchAlias = X509CertUtil.matchCertificate(caCertificates, rootCert);
					}

					// Match against Windows Trusted Root Certificates KeyStore
					if ((windowsTrustedRootCertificates != null) && (matchAlias == null)) {
						matchAlias = X509CertUtil.matchCertificate(windowsTrustedRootCertificates, rootCert);
					}

					if (matchAlias == null) // Match against current KeyStore
					{
						matchAlias = X509CertUtil.matchCertificate(keyStore, rootCert);
					}

					if (matchAlias == null) {
						// No match for the root certificate - display the
						// certificate to the user
						// for
						// confirmation
						JOptionPane.showMessageDialog(frame,
								res.getString("ImportCaReplyAction.NoMatchRootCertCaReplyConfirm.message"),
								res.getString("ImportCaReplyAction.ImportCaReply.Title"),
								JOptionPane.INFORMATION_MESSAGE);

						DViewCertificate dViewCertificate = new DViewCertificate(frame, MessageFormat.format(
								res.getString("ImportCaReplyAction.CertDetailsFile.Title"), caReplyFile.getName()),
								new X509Certificate[] { rootCert }, null, DViewCertificate.NONE);
						dViewCertificate.setLocationRelativeTo(frame);
						dViewCertificate.setVisible(true);

						int selected = JOptionPane.showConfirmDialog(frame,
								res.getString("ImportCaReplyAction.AcceptCaReply.message"),
								res.getString("ImportCaReplyAction.ImportCaReply.Title"), JOptionPane.YES_NO_OPTION);
						if (selected != JOptionPane.YES_OPTION) {
							return;
						}

						newCertChain = certs;
					} else {
						newCertChain = certs;
					}
				}
				/*
				 * Single X.509 certificate reply - try and establish a chain of
				 * trust from the certificate and ending with a root CA
				 * self-signed certificate
				 */
				else {
					// Establish trust against current KeyStore
					ArrayList<KeyStore> compKeyStores = new ArrayList<KeyStore>();
					compKeyStores.add(keyStore);

					if (caCertificates != null) {
						// Establish trust against CA Certificates KeyStore
						compKeyStores.add(caCertificates);
					}

					if (windowsTrustedRootCertificates != null) {
						// Establish trust against Windows Trusted Root
						// Certificates KeyStore
						compKeyStores.add(windowsTrustedRootCertificates);
					}

					X509Certificate[] trustChain = X509CertUtil.establishTrust(certs[0],
							compKeyStores.toArray(new KeyStore[compKeyStores.size()]));

					if (trustChain != null) {
						newCertChain = trustChain;
					} else {
						// Cannot establish trust for the certificate - fail
						JOptionPane.showMessageDialog(frame,
								res.getString("ImportCaReplyAction.NoTrustCaReply.message"),
								res.getString("ImportCaReplyAction.ImportCaReply.Title"), JOptionPane.WARNING_MESSAGE);
						return;
					}
				}
			}

			keyStore.deleteEntry(alias);

			keyStore.setKeyEntry(alias, privateKey, password.toCharArray(), newCertChain);

			currentState.append(newState);

			kseFrame.updateControls(true);

			JOptionPane.showMessageDialog(frame, res.getString("ImportCaReplyAction.ImportCaReplySuccessful.message"),
					res.getString("ImportCaReplyAction.ImportCaReply.Title"), JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			DError.displayError(frame, ex);
		}
	}

	private File chooseCaFile() {
		JFileChooser chooser = FileChooserFactory.getCaReplyFileChooser();
		chooser.setCurrentDirectory(CurrentDirectory.get());
		chooser.setDialogTitle(res.getString("ImportCaReplyAction.ImportCaReply.Title"));
		chooser.setMultiSelectionEnabled(false);

		int rtnValue = chooser.showDialog(frame, res.getString("ImportCaReplyAction.ImportCaReply.button"));
		if (rtnValue == JFileChooser.APPROVE_OPTION) {
			File openFile = chooser.getSelectedFile();
			CurrentDirectory.updateForFile(openFile);
			return openFile;
		}

		return null;
	}

	private X509Certificate[] openCaReply(File caReply) {
		try {
			X509Certificate[] certs = X509CertUtil.loadCertificates(new FileInputStream(caReply));

			if (certs.length == 0) {
				JOptionPane.showMessageDialog(frame,
						MessageFormat.format(res.getString("ImportCaReplyAction.NoCertsFound.message"), caReply),
						res.getString("ImportCaReplyAction.OpenCaReply.Title"), JOptionPane.WARNING_MESSAGE);
			}

			return certs;
		} catch (FileNotFoundException ex) {
			JOptionPane.showMessageDialog(frame,
					MessageFormat.format(res.getString("ImportCaReplyAction.NoReadFile.message"), caReply),
					res.getString("ImportCaReplyAction.OpenCaReply.Title"), JOptionPane.WARNING_MESSAGE);
			return null;
		} catch (Exception ex) {
			String problemStr = MessageFormat.format(res.getString("ImportCaReplyAction.NoOpenCaReply.Problem"),
					caReply.getName());

			String[] causes = new String[] { res.getString("ImportCaReplyAction.NotCaReply.Cause"),
					res.getString("ImportCaReplyAction.CorruptedCaReply.Cause") };

			Problem problem = new Problem(problemStr, causes, ex);

			DProblem dProblem = new DProblem(frame, res.getString("ImportCaReplyAction.ProblemOpeningCaReply.Title"),
					APPLICATION_MODAL, problem);
			dProblem.setLocationRelativeTo(frame);
			dProblem.setVisible(true);

			return null;
		}
	}
}