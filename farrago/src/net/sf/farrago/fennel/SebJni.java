/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2009-2009 The Eigenbase Project
// Copyright (C) 2009-2009 SQLstream, Inc.
// Copyright (C) 2009-2009 LucidEra, Inc.
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package net.sf.farrago.fennel;

import net.sf.farrago.fem.fennel.*;

import org.eigenbase.util.*;

import java.sql.*;

/**
 * JNI interface for storage engine bridging.
 *
 * @author John Sichi
 * @version $Id$
 */
public abstract class SebJni
{
    //~ Static fields/initializers ---------------------------------------------

    static {
        Util.loadLibrary("farrago_seb");
    }

    //~ Methods ----------------------------------------------------------------

    public static native void registerStreamFactory(long hStreamGraph);

    public static native long executeJavaCmd(FemCmd cmd, long execHandle)
        throws SQLException;
}

// End SebJni.java