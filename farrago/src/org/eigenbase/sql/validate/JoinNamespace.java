/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2004-2005 The Eigenbase Project
// Copyright (C) 2004-2005 Disruptive Tech
// Copyright (C) 2005-2005 Red Square, Inc.
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
package org.eigenbase.sql.validate;

import org.eigenbase.sql.SqlJoin;
import org.eigenbase.sql.SqlNode;
import org.eigenbase.reltype.RelDataType;

/**
 * Namespace representing the row type produced by joining two relations.
 *
 * @author jhyde
 * @version $Id$
 * @since Mar 25, 2003
 */
class JoinNamespace extends AbstractNamespace
{
    private final SqlJoin join;

    JoinNamespace(SqlValidatorImpl validator, SqlJoin join)
    {
        super(validator);
        this.join = join;
    }

    protected RelDataType validateImpl()
    {
        final RelDataType leftType =
            validator.getNamespace(join.getLeft()).getRowType();
        final RelDataType rightType =
            validator.getNamespace(join.getRight()).getRowType();
        final RelDataType[] types = {leftType, rightType};
        return validator.typeFactory.createJoinType(types);
    }

    public SqlNode getNode()
    {
        return join;
    }
}

// End JoinNamespace.java
