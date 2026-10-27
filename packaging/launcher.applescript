-- Source of "Start Pagewright.app" (the double-click launcher at the repo root).
-- Rebuild it with packaging/make-launcher.sh.
-- It builds the real "Pagewright.app" into dist/ the first time (finding or downloading Java as needed),
-- then simply opens it on every later click.
property appName : "Pagewright"

on run
	startEditor("")
end run

on open theFiles
	set firstPath to POSIX path of (item 1 of theFiles)
	if firstPath ends with ".pdf" or firstPath ends with ".PDF" then
		startEditor(firstPath)
	else
		display dialog "Drop a .pdf file on this icon, or double-click it to start with no file." buttons {"OK"} default button "OK" with title appName
	end if
end open

on startEditor(pdfPath)
	set bundlePath to POSIX path of (path to me)
	set repoDir to do shell script "dirname " & quoted form of (text 1 thru -2 of bundlePath)
	set q to quoted form of repoDir
	set logFile to "\"$HOME/Library/Logs/Pagewright/launch.log\""

	set firstRun to (do shell script "test -d " & q & "/dist/'Pagewright.app' && echo no || echo yes")
	if firstRun is "yes" then
		display notification "First launch: setting things up. This can take a few minutes and needs internet." with title appName
	end if

	set openArg to ""
	if pdfPath is not "" then set openArg to " " & quoted form of pdfPath
	try
		with timeout of 3600 seconds
			do shell script "mkdir -p \"$HOME/Library/Logs/Pagewright\" && cd " & q & " && sh packaging/start-mac.sh" & openArg & " > " & logFile & " 2>&1"
		end timeout
	on error
		display dialog "Pagewright could not start. Details are in ~/Library/Logs/Pagewright/launch.log" buttons {"OK"} default button "OK" with icon stop with title appName
	end try
end startEditor
