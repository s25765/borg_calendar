/*
 This file is part of BORG.

 BORG is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 BORG is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with BORG; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

 Copyright 2003 by Mike Berger
 */
/*
 * popups.java
 *
 * Created on January 16, 2004, 3:08 PM
 */

package net.sf.borg.control;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.TimerTask;

import net.sf.borg.common.Errmsg;
import net.sf.borg.common.PrefName;
import net.sf.borg.common.Prefs;
import net.sf.borg.common.SendJavaMail;
import net.sf.borg.model.AppointmentIcalAdapter;
import net.sf.borg.model.AppointmentModel;
import net.sf.borg.model.AppointmentVcalAdapter;
import net.sf.borg.model.TaskModel;
import net.sf.borg.model.beans.Appointment;
import net.sf.borg.model.beans.Task;

/**
 *
 * @author mberger
 */
public class EmailReminder {

	public EmailReminder() {
		
		timer_ = new java.util.Timer();
		// start at next minute on system clock
		GregorianCalendar cur = new GregorianCalendar();
		int secs_left = 60 - cur.get(Calendar.SECOND);
		timer_.schedule(new TimerTask() {
			public void run() {
				email_chk();
			}
		}, secs_left * 1000, 60 * 1000);

	}

	private Collection doneList_ = new ArrayList();
	private java.util.Timer timer_ = null;

	public void destroy() {
		timer_.cancel();
	}

	private void email_chk() {

		String enable = Prefs.getPref(PrefName.INDIVEMAILENABLED);
		if (enable.equals("false"))
			return;

		// determine if we are popping up public/private appts
		boolean showpub = false;
		boolean showpriv = false;
		String sp = Prefs.getPref(PrefName.SHOWPUBLIC);
		if (sp.equals("true"))
			showpub = true;
		sp = Prefs.getPref(PrefName.SHOWPRIVATE);
		if (sp.equals("true"))
			showpriv = true;

		// get the key for today in the data model
		int key = AppointmentModel.dkey(new GregorianCalendar());

		// get the list of the today's appts
		Collection l = AppointmentModel.getReference().getAppts(key);
		if (l != null) {
			Iterator it = l.iterator();
			Appointment appt;

			// iterate through the day's appts
			while (it.hasNext()) {

				Integer ik = (Integer) it.next();
				
				// skip appt if it is already in the done list
				if (doneList_.contains(ik))
					continue;
				
				try {
					// read the appt record from the data model
					appt = AppointmentModel.getReference().getAppt(
							ik.intValue());
					
					// check if we should show it based on public/private flags
					if (appt.getPrivate()) {
						if (!showpriv)
							continue;
					} else {
						if (!showpub)
							continue;
					}

					// don't popup "notes"
					if (AppointmentModel.isNote(appt))
						continue;

					Date d = appt.getDate();

					// set appt time for computation
					GregorianCalendar now = new GregorianCalendar();
					GregorianCalendar acal = new GregorianCalendar();
					acal.setTime(d);

					// need to set appt time to today in case it is a repeating
					// appt. if it is a repeat,
					// the time will be right, but the day will be the day of
					// the first repeat
					acal.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
							now.get(Calendar.DATE));

					// skip the appt if it is outside the time frame of the
					// reminder requests
					long mins_to_go = acal.getTimeInMillis()/(1000*60) -
										now.getTimeInMillis()/(1000*60);

					if (mins_to_go < 0 || mins_to_go > Prefs.getIntPref(PrefName.INDIVEMAILMINS))
						continue;

					// EMAIL the reminder
					emailMeeting(appt);
					
					doneList_.add(ik);


				} catch (Exception e) {
					Errmsg.errmsg(e);
				}
			}
		}

		
	}

	// send an email of the next day's appointments if the user has requested
	// this and
	// such an email has not been sent today yet.
	static public void sendDailyEmailReminder(Calendar emailday) throws Exception{
	
		// check if the email feature has been enabled
		String email = Prefs.getPref(PrefName.EMAILENABLED);
		if (email.equals("false"))
			return;
	
		// get the SMTP host and address
		String host = Prefs.getPref(PrefName.EMAILSERVER);
		String addr = Prefs.getPref(PrefName.EMAILADDR);
	
		if (host.equals("") || addr.equals(""))
			return;
	
		Calendar cal = new GregorianCalendar();
	
		// if no date passed, the timer has gone off and we need to check if we
		// can send
		// email now
		int doy = -1;
		if (emailday == null) {
			// get the last day that email was sent
			int lastday = Prefs.getIntPref(PrefName.EMAILLAST);
	
			// if email was already sent today - don't send again
			doy = cal.get(Calendar.DAY_OF_YEAR);
			if (doy == lastday)
				return;
	
			// create the calendar model key for tomorrow
			cal.add(Calendar.DATE, 1);
		} else {
			// just send email for the given day
			cal = emailday;
		}
	
		int key = AppointmentModel.dkey(cal.get(Calendar.YEAR), cal
				.get(Calendar.MONTH), cal.get(Calendar.DATE));
	
		// tx is the contents of the email
		String tx = "Appointments for "
				+ DateFormat.getDateInstance().format(cal.getTime()) + "\n";
	
		// get the list of appts for tomorrow
		Collection l = AppointmentModel.getReference().getAppts(key);
		if (l != null) {
	
			Iterator it = l.iterator();
			Appointment appt;
	
			// iterate through the day's appts
			while (it.hasNext()) {
	
				Integer ik = (Integer) it.next();
	
				try {
					// read the appointment from the calendar model
					appt = AppointmentModel.getReference().getAppt(
							ik.intValue());
	
					// get the appt flags to see if the appointment is private
					// if so, don't include it in the email
					if (appt.getPrivate())
						continue;
	
					if (!AppointmentModel.isNote(appt)) {
						// add the appointment time to the email if it is not a
						// note
						Date d = appt.getDate();
						SimpleDateFormat df = AppointmentModel.getTimeFormat();
						tx += df.format(d) + " ";
					}
	
					// add the appointment text
					tx += appt.getText();
					tx += "\n";
				} catch (Exception e) {
					System.out.println(e.toString());
					return;
				}
			}
	
		}
	
		// load any task tracker items for the email
		// these items are cached in the calendar model
		// by date - but the taskmodel is the real owner of them
		l = TaskModel.getReference().get_tasks(key);
		if (l != null) {
	
			Iterator it = l.iterator();
	
			while (it.hasNext()) {
				// add each task to the email - and remove newlines
	
				Task task = (Task) it.next();
				tx += "Task[" + task.getTaskNumber() + "] ";
				String de = task.getDescription();
				tx += de.replace('\n', ' ');
				tx += "\n";
			}
		}

		// send the email using SMTP

		StringTokenizer stk = new StringTokenizer(addr, ",;");
		while (stk.hasMoreTokens()) {
		    String a = stk.nextToken();
		    if (!a.equals("")) {
			SendJavaMail.sendMail(host, tx, a.trim(), a.trim(), Prefs
				.getPref(PrefName.EMAILUSER), Prefs
				.getPref(PrefName.EMAILPASS));
			// String ed = Prefs.getPref(PrefName.EMAILDEBUG);
			// if (ed.equals("1"))
			// Errmsg.notice(s);
		    }
		}



		// record that we sent email today
		if (doy != -1)
			Prefs.putPref(PrefName.EMAILLAST, new Integer(doy));
	
		return;
	}

	public static void emailMeeting(Appointment mtg) {
	
		// get the SMTP host and address
		String host = Prefs.getPref(PrefName.EMAILSERVER);
		String addr = Prefs.getPref(PrefName.EMAILADDR);
	
		if (host.equals("") || addr.equals(""))
			return;
	
		// send the email using SMTP
		try {
			String msg = "";
			Date d = mtg.getDate();
			SimpleDateFormat df = AppointmentModel.getTimeFormat();
			msg += df.format(d) + " " + mtg.getText();
			
			StringTokenizer stk = new StringTokenizer(addr, ",;");
			while (stk.hasMoreTokens()) {
				String a = stk.nextToken();
				if (!a.equals("")) {
					SendJavaMail.sendCalMail(host, msg, a.trim(), a
							.trim(), Prefs.getPref(PrefName.EMAILUSER), Prefs
							.getPref(PrefName.EMAILPASS),
							AppointmentIcalAdapter.exportIcalToString(mtg),
							AppointmentVcalAdapter.exportVcalToString(mtg));
	
					// Errmsg.notice(s);
				}
			}
	
		} catch (Exception e) {
			Errmsg.errmsg(e);
		}
	
		return;
	}


}
