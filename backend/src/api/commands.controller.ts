import { Request, Response, NextFunction } from 'express';
import { commandService } from '../commands/commands.service';

export async function dispatchCommandHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const command = await commandService.dispatchCommand(req.body);
    res.status(201).json({ command });
  } catch (err) {
    next(err);
  }
}

export async function getCommandByIdHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const command = await commandService.getCommandById(req.params.commandId);
    res.json({ command });
  } catch (err) {
    next(err);
  }
}

export async function recordCommandResponseHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { status, result } = req.body;
    const command = await commandService.recordCommandResponse(req.params.commandId, status, result);
    res.json({ command });
  } catch (err) {
    next(err);
  }
}
